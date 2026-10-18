package dev.turnfab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A whole session in a single {@code .md} file: YAML frontmatter carrying the ChatMemory, with the raw
 * markdown transcript as the body. Saving one of these and loading it back is meant to leave the app
 * where it was left off, so the file has to hold everything the running app holds: the message window,
 * the turn counter, and the system prompt that gets stamped onto every request.
 * <p>
 * The messages ride along as JSON, not YAML: LangChain4j already serializes every message type faithfully
 * ({@link ChatMessageSerializer}), including thinking, tool requests and tool results, so YAML's job here
 * is to be an envelope around one opaque string rather than to re-invent a schema for each message type.
 * That string is pretty-printed first so it lands in a literal block scalar and stays legible.
 * <p>
 * Two rules keep the envelope from ever fighting the transcript:
 * <ul>
 *     <li>The opening delimiter is only ever recognized on line 1, and closes at the next line that is
 *     exactly {@code ---}, so a horizontal rule inside the markdown cannot be mistaken for one.</li>
 *     <li>Every save re-reads what it just wrote and compares, so a session file is never silently
 *     written wrong: if the memory or the prompt failed to survive YAML's scalar rules, the save fails
 *     loudly instead of leaving a file that loads back as something else.</li>
 * </ul>
 */
public class SessionFile {

	public static final int SCHEMA_VERSION = 1;
	private static final String DELIM = "---";

	/**
	 * Literal block style is what keeps a multi-line payload readable: without it, Jackson quotes every
	 * string, and a few hundred messages become one ten-thousand-character escaped line.
	 */
	private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
			.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)   // the delimiter lines are ours to write
			.enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
			.build());

	/** Used only to re-indent the messages JSON, so it breaks into lines instead of one long one. */
	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * Everything one session is made of. {@code savedAt} is written on save and read on load, so the
	 * convenience constructor below leaves it {@code null} rather than making callers guess.
	 */
	public record Session(int turnNumber, String modelName, String savedAt, String systemPrompt,
						  List<ChatMessage> messages, String markdown) {

		Session(int turnNumber, String modelName, String systemPrompt, List<ChatMessage> messages, String markdown) {
			this(turnNumber, modelName, null, systemPrompt, messages, markdown);
		}

		public Session {
			messages = (messages == null) ? List.of() : List.copyOf(messages);
		}
	}

	private SessionFile() {}

	/** Writes the session file, then verifies it: a session file that lies is worse than no session file. */
	public static void save(Path file, Session snap) throws IOException {
		String messagesJson = ChatMessageSerializer.messagesToJson(snap.messages());

		ObjectNode fm = YAML.createObjectNode();
		fm.put("turnfabSession", SCHEMA_VERSION);
		fm.put("savedAt", Instant.now().toString());
		fm.put("turnNumber", snap.turnNumber());
		fm.put("model", snap.modelName());
		fm.put("systemPrompt", blankIfNull(snap.systemPrompt()));
		fm.put("messagesJson", prettyJson(messagesJson));

		String body = blankIfNull(snap.markdown()).replace("\r", "");
		if (!body.isEmpty() && !body.endsWith("\n")) body = body + "\n";

		String head = YAML.writeValueAsString(fm).replace("\r", "");
		if (!head.endsWith("\n")) head = head + "\n";
		Files.writeString(file, DELIM + "\n" + head + DELIM + "\n" + body, StandardCharsets.UTF_8);

		verify(file, snap.turnNumber(), snap.systemPrompt(), messagesJson);
	}

	/**
	 * Reads a session file. Empty when the file is not a session at all, which is the case for every
	 * plain markdown transcript Turnfab has ever written: those load as view-only, no memory.
	 *
	 * @throws IOException if the file claims to be a session and cannot be read as one
	 */
	public static Optional<Session> load(Path file) throws IOException {
		if (!Files.isRegularFile(file)) return Optional.empty();
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		if (lines.isEmpty() || !lines.get(0).equals(DELIM)) return Optional.empty();

		int close = -1;
		for (int i = 1; i < lines.size(); i++) {
			if (lines.get(i).equals(DELIM)) { close = i; break; }
		}
		if (close < 0) return Optional.empty();

		JsonNode fm;
		try {
			fm = YAML.readTree(String.join("\n", lines.subList(1, close)));
		} catch (Exception notYaml) {
			return Optional.empty();   // a "---" that was only ever a horizontal rule
		}
		if (fm == null || !fm.hasNonNull("turnfabSession")) return Optional.empty();

		int version = fm.get("turnfabSession").asInt();
		if (version > SCHEMA_VERSION) {
			throw new IOException("Session file "+file+" is schema version "+version
					+ ", this build of Turnfab reads version "+SCHEMA_VERSION);
		}

		List<ChatMessage> messages;
		try {
			messages = ChatMessageDeserializer.messagesFromJson(fm.path("messagesJson").asText(null));
		} catch (Exception unreadable) {
			throw new IOException("Could not decode the chat memory in " + file, unreadable);
		}

		String markdown = String.join("\n", lines.subList(close + 1, lines.size()));
		return Optional.of(new Session(fm.path("turnNumber").asInt(0),
				textOrNull(fm.get("model")), textOrNull(fm.get("savedAt")),
				fm.path("systemPrompt").asText(""), messages, markdown));
	}

	/** Reads the file back and asks whether the payload survived the trip through YAML. */
	private static void verify(Path file, int turnNumber, String systemPrompt, String messagesJson)
			throws IOException {
		Optional<Session> back = load(file);
		if (back.isEmpty()) {
			throw new IOException("Session written to " + file + " does not read back as a session.");
		}
		Session s = back.get();
		if (s.turnNumber() != turnNumber) {
			throw new IOException("turnNumber did not survive the round trip through " + file);
		}
		// A literal block scalar is free to drop the line breaks it ends with, so this compares everything
		// but trailing breaks: a difference there is a blank line at the end of the prompt, not the prompt.
		if (!blankIfNull(s.systemPrompt()).stripTrailing().equals(blankIfNull(systemPrompt).stripTrailing())) {
			throw new IOException("The system prompt did not survive the round trip through " + file);
		}
		if (!ChatMessageSerializer.messagesToJson(s.messages()).equals(messagesJson)) {
			throw new IOException("The chat memory did not survive the round trip through " + file);
		}
	}

	private static String prettyJson(String compactJson) throws IOException {
		try {
			return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(compactJson));
		} catch (Exception malformed) {
			throw new IOException("Chat memory did not serialize to parseable JSON", malformed);
		}
	}

	private static String blankIfNull(String s) {
		return (s == null) ? "" : s;
	}

	private static String textOrNull(JsonNode node) {
		return (node == null || node.isNull()) ? null : node.asText();
	}
}
