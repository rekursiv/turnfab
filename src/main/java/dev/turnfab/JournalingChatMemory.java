package dev.turnfab;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/**
 * A {@link ChatMemory} decorator that records the whole trajectory of a session while leaving
 * all actual memory behaviour to the wrapped memory.
 * <p>
 * The journal stores <em>references</em>, not copies: {@link ChatMessage}s are immutable, so
 * nothing is duplicated and nothing can be mutated behind our back. When the wrapped memory
 * evicts a message it simply drops its reference, and ours keeps the message alive - which is
 * all "evicted instead of deleted" means here. There is no eviction flag to maintain, and no
 * eviction logic to reimplement: this class never decides what fits in the context window.
 * <p>
 * So the journal holds every message the window ever held, in the order it first held them,
 * and the active/evicted split is computed live from the wrapped memory's own
 * {@code messages()}. That means the split can never drift out of sync with the window.
 * <p>
 * Caveats worth knowing:
 * <ul>
 *     <li>This records <em>what actually happened</em>, not <em>what the model saw</em>. RAG
 *     augmentation, {@code systemMessageTransformer} and tool-compensation rewrites change the
 *     outgoing request, not the memory, so they are not reflected here. For that, hook a
 *     {@code ChatModelListener}.</li>
 *     <li>{@link #clear()} empties the window but keeps the journal, so a "new chat" starts a
 *     new epoch without destroying the log.</li>
 *     <li>The retained set grows for the life of the session. If that ever hurts, spool entries
 *     that fall out of the window to disk instead of holding them.</li>
 * </ul>
 */
public class JournalingChatMemory implements ChatMemory {

	/** One message, recorded the first time it appeared in the context window. */
	public record Entry(int seq, ChatMessage message, long addedAtMillis, int turn) {

		@Override
		public String toString() {
			return message.type() + " #" + seq + " (turn " + turn + ") " + preview(message);
		}

		private static String preview(ChatMessage message) {
			String s = String.valueOf(message);
			return s.length() > 70 ? s.substring(0, 69) + "..." : s;
		}
	}

	private final ChatMemory delegate;
	private final IntSupplier turnNumber;
	private final List<Entry> journal = new ArrayList<>();

	/** The messages already recorded, by identity: messages can be equal without being new. */
	private final Set<ChatMessage> recorded = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * @param delegate    the memory that actually holds and evicts the window
	 * @param turnNumber  supplies the current turn number, so entries can be grouped by turn;
	 *                    may be a view of caller state that changes between calls
	 */
	public JournalingChatMemory(ChatMemory delegate, IntSupplier turnNumber) {
		this.delegate = delegate;
		this.turnNumber = turnNumber;
	}

	//////////////////////////////////////////////////////////
	// ChatMemory - everything delegated, then recorded

	@Override
	public Object id() {
		return delegate.id();
	}

	@Override
	public List<ChatMessage> messages() {
		return delegate.messages();
	}

	@Override
	public void add(ChatMessage message) {
		synchronized (this) {
			delegate.add(message);
			record();
		}
	}

	@Override
	public void add(Iterable<ChatMessage> messages) {
		synchronized (this) {
			delegate.add(messages);
			record();
		}
	}

	@Override
	public void set(Iterable<ChatMessage> messages) {
		synchronized (this) {
			delegate.set(messages);
			record();
		}
	}

	@Override
	public void clear() {
		synchronized (this) {
			delegate.clear();
			record();
		}
	}

	@Override
	public CompletableFuture<Void> addAsync(List<ChatMessage> messages) {
		return delegate.addAsync(messages).thenRun(this::record);
	}

	@Override
	public CompletableFuture<Void> setAsync(List<ChatMessage> messages) {
		return delegate.setAsync(messages).thenRun(this::record);
	}

	@Override
	public CompletableFuture<List<ChatMessage>> messagesAsync() {
		return delegate.messagesAsync();
	}

	//////////////////////////////////////////////////////////
	// Trajectory

	/** Everything ever seen by the window, oldest first, including what is no longer active. */
	public synchronized List<Entry> journal() {
		return List.copyOf(journal);
	}

	/** Entries currently in the model's context window, in journal order. */
	public List<Entry> active() {
		return filter(true);
	}

	/** Entries the wrapped memory has evicted, in journal order. */
	public List<Entry> evicted() {
		return filter(false);
	}

	private synchronized List<Entry> filter(boolean active) {
		Set<ChatMessage> window = Collections.newSetFromMap(new IdentityHashMap<>());
		window.addAll(delegate.messages());
		List<Entry> matching = new ArrayList<>();
		for (Entry entry : journal) {
			if (window.contains(entry.message()) == active) {
				matching.add(entry);
			}
		}
		return List.copyOf(matching);
	}

	/** Appends the entries the current window holds that were not recorded yet. */
	private synchronized void record() {
		int turn = turnNumber.getAsInt();
		long now = System.currentTimeMillis();
		for (ChatMessage message : delegate.messages()) {
			if (recorded.add(message)) {
				journal.add(new Entry(journal.size(), message, now, turn));
			}
		}
	}

}
