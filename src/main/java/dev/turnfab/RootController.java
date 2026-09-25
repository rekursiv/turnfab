package dev.turnfab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

import com.cathive.fx.guice.FXMLController;
import com.cathive.fx.guice.GuiceFXMLLoader;
import com.google.inject.Inject;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;


@FXMLController
public class RootController {

	private static final boolean DEBUG_CHAT_MODEL = false;
	private static final int MAX_TOOL_CALL_DETAIL_CHUNKS = 20;
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");
    private static final int MAX_TOKENS = 180000;  // model length is 262144, leave headroom for long replies

	@Inject private Logger log;
	@Inject private TurnfabConfig cfg;
	@Inject private GuiceFXMLLoader fxmlLoader;

	@Inject private ToolManager toolManager;
	@Inject private PromptManager promptManager;

	@FXML private TabPane tabPane;
	@FXML private StackPane stpRenderedConvo;
	@FXML private TextArea txaPrompt;
	@FXML private TextArea txaToSend;
	@FXML private Button btnSend;

	@FXML private Label lblCtxUsage;
	@FXML private Label lblTokPerSec;


	private MarkstreamView msView = new MarkstreamView();

	private StringBuilder systemPrompt = new StringBuilder();
	private Bot bot;
	private Section currentSection = Section.NONE;
	private int currentToolIndex = -1;
	private int toolCallChunks = 0;
	private int turnNumber = 0;
	private StringBuilder mdRaw = new StringBuilder();

	private boolean armCancel = false;

	// The window is what the model sees; JournalingChatMemory also keeps the full session
	// trajectory, including whatever the MAX_TOKENS window has already evicted.
	private JournalingChatMemory chatMemory;

	// streaming stats
	private long streamStartNanos;
	private int outputTokens;
	private int inputTokens;
	private long lastLabelUpdateNanos;

	// When true, the stats recompute is deferred until the first streaming callback of the
	// next round: tool results are only persisted to chat memory after every onToolExecuted
	// has fired, so reading the memory any earlier would miss them.
	private volatile boolean statsRecomputePending = false;

	private enum Section { NONE, THINKING, RESPONSE, TOOL_CALL }

	private TokenCountEstimator tcEst = new OpenAiTokenCountEstimator("o200K_BASE"); // hack to trigger this.encoding = ENCODING_REGISTRY.getEncoding(O200K_BASE);

	@FXML
	private void initialize() {
		stpRenderedConvo.getChildren().add(msView.getView());
//		tabPane.getSelectionModel().select(1);
	}

	private void initBot() {
		OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
				.modelName(cfg.model_name)
				.baseUrl(cfg.model_base_url)
				.apiKey(cfg.model_key)
				.httpClientBuilder(new JdkHttpClientBuilder()
						.httpClientBuilder(java.net.http.HttpClient.newBuilder()
								.version(java.net.http.HttpClient.Version.HTTP_1_1)))
				.logRequests(DEBUG_CHAT_MODEL)
				.logResponses(DEBUG_CHAT_MODEL)
				.returnThinking(true)
				.sendThinking(true, "reasoning")
				.timeout(Duration.ofSeconds(30))
				.build();


		bot = AiServices.builder(Bot.class)
				.streamingChatModel(new ThinkingFirstStreamingModel(model))
				.systemMessageTransformer(systemMessage -> buildSystemPrompt())
				.toolProvider(toolManager.getProvider())
				.chatMemory(chatMemory = new JournalingChatMemory(
						TokenWindowChatMemory.withMaxTokens(MAX_TOKENS, tcEst), () -> turnNumber))
				.build();

		log.info("Model and AI Service Ready.");
	}



	//////////////////////////////////////////////////////////

	@FXML
	public void onInitBot() {
		initBot();
	}

	@FXML
	public void onInitMcp() {
		toolManager.init();
		toolManager.printEnabledTools();
	}

	private String buildSystemPrompt() {
		if (systemPrompt.isEmpty()) {
			systemPrompt.append(readResource("system_prompts/coder.md"));
			systemPrompt.append("\nToday's date is " + LocalDate.now().format(DATE_FORMATTER) + ".");
		}
		return systemPrompt.toString();
	}

	@FXML
	public void onBuildPrompt() {
		buildLc4jPrompt();
//		buildPromptFromTabFiles();
	}

	private void buildPromptFromTabFiles() {
		txaPrompt.clear();
		txaPrompt.appendText(promptManager.readAllTabs(toolManager.getMainprjMcpClient()));
	}

	private void buildLc4jPrompt() {
		txaPrompt.clear();
		txaPrompt.appendText("Full searchable source code for LangChain4j is available with langchain4j_src-* tools.\n\n");
		txaPrompt.appendText("LangChain4j documentation: ");
		txaPrompt.appendText(promptManager.listDirTree(toolManager.getLc4jMcpClient(), "docs/docs", 3));
//		txaPrompt.appendText(promptManager.readFile(toolManager.getMainprjMcpClient(), "src/main/java/dev/turnfab/RootController.java"));
		txaPrompt.appendText(promptManager.readActiveTab(toolManager.getMainprjMcpClient()));
	}

	private void buildMsPrompt() {
		txaPrompt.clear();
		txaPrompt.appendText("Documentation for markstream-vue:");  // dirPath: docs, depth: 2
//		txaPrompt.appendText(promptManager.msvDocs(toolManager.getMsvsrcMcpClient()));
		txaPrompt.appendText("Location of files that render markdown in my app (main_project):");  // dirPath markstream-page, depth: 2
//		txaPrompt.appendText(promptManager.mspLoc(toolManager.getMainprjMcpClient()));
		txaPrompt.appendText("main_project-read_file: file_path = src/main/java/dev/turnfab/RootController.java");
//		txaPrompt.appendText(promptManager.rootCtlr(toolManager.getMainprjMcpClient()));
	}

	@FXML
	public void onSendPrompt() {
		beginTurn(txaPrompt.getText());
		tabPane.getSelectionModel().select(1);
	}

	@FXML
	public void onTest() {
		List<JournalingChatMemory.Entry> journal = chatMemory.journal();
		for (JournalingChatMemory.Entry entry : journal) {
			System.out.println(entry.toString());
		}
//		txaPrompt.clear();
//		txaPrompt.appendText(toolManager.getMcpInst());
//		txaPrompt.appendText(promptManager.getAllTabPaths(toolManager.getMainprjMcpClient()));
	}

	@FXML
	public void onClearPrompt() {
		txaPrompt.clear();
	}

	//////////////////////////////////////////////////////////

	@FXML
	public void onSend() {
		beginTurn(txaToSend.getText());
		txaToSend.clear();
	}

	@FXML
	public void onSaveMd() throws IOException {
		File selectedFile = askUserWhereToSave();
		if (selectedFile!=null) {
			System.out.println("Saving markdown to: "+selectedFile.getAbsolutePath());
			Files.writeString(selectedFile.toPath(), mdRaw.toString());
		}
	}

	@FXML
	public void onLoadMd() throws IOException {
		File selectedFile = askUserWhatToLoad();
		System.out.println("Loading "+selectedFile.getAbsolutePath());
		String content = new String(Files.readAllBytes(selectedFile.toPath()));
		msView.reset();
		msView.load(content);
		mdRaw = new StringBuilder(content);
	}

	@FXML
	public void onViewMd() throws IOException {
		showRawMarkdown();
	}

	@FXML
	public void onCancel() {
		btnSend.setDisable(false);
		armCancel = true;
	}

	private void beginTurn(String toSend) {
		if (bot==null) {
			log.warning("Bot has not been initialized!");
			return;
		}
		armCancel = false;
		++turnNumber;
		btnSend.setDisable(true);
		currentSection = Section.NONE;
		currentToolIndex = -1;

		if (turnNumber==1) {
			buildSystemPrompt();
			appendMd("### ==System Prompt:==\n");
			appendMd(systemPrompt.toString());
		}

		appendMd("\n\n## ==Turn "+turnNumber+"==\n");
		if (toSend.length()>900) appendMd("..."+toSend.substring(toSend.length()-900).replace("```", ""));
		else appendMd(toSend);

		// reset streaming stats
		inputTokens = tcEst.estimateTokenCountInMessages(chatMemory.messages());
		outputTokens = 0;
		streamStartNanos = System.nanoTime();
		lastLabelUpdateNanos = 0;
		statsRecomputePending = false;

		TokenStream stream = bot.chat(toSend,
				OpenAiChatRequestParameters.builder()
						.reasoningEffort("medium")
						.build());

		stream
				.onPartialThinkingWithContext((PartialThinking partialThinking, PartialThinkingContext context) -> {
					if (partialThinking.text() == null || partialThinking.text().isEmpty()) return;
					recomputeStatsIfPending();
					if (currentSection != Section.THINKING) {
						closeSection();
						appendMd("\n\n==Thinking:==\n");
						currentSection = Section.THINKING;
					}
					appendMd(partialThinking.text());
					outputTokens += tcEst.estimateTokenCountInText(partialThinking.text());
					updateStatsLabels();
					if (armCancel) {
						context.streamingHandle().cancel();
						appendMd("\n\n==CANCELLED==\n\n");
						endTurn();
					}
				})
				.onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext context) -> {
					if (partialResponse.text() == null || partialResponse.text().isEmpty()) return;
					recomputeStatsIfPending();
					if (currentSection == Section.TOOL_CALL) return; // don't steal section mid tool-call streaming
					if (currentSection != Section.RESPONSE) {
						closeSection();
						appendMd("\n\n==Response:==\n");
						currentSection = Section.RESPONSE;
					}
					appendMd(partialResponse.text());
					outputTokens += tcEst.estimateTokenCountInText(partialResponse.text());
					updateStatsLabels();
					if (armCancel) {
						context.streamingHandle().cancel();
						appendMd("\n\n==CANCELLED==\n\n");
						endTurn();
					}
				})
				.onPartialToolCall(partialToolCall -> {
					recomputeStatsIfPending();
					if (currentSection != Section.TOOL_CALL
							|| partialToolCall.index() != currentToolIndex) {
						if (currentSection == Section.TOOL_CALL) {
							appendMd("\n```\n"); // close the previous call's fenced block
						}
						closeSection();
						appendMd("\n\n==Tool Call:==   *"+partialToolCall.id()+" : "+partialToolCall.name()+"*\n```json\n");
						currentSection = Section.TOOL_CALL;
						currentToolIndex = partialToolCall.index();
						toolCallChunks = 0;
					}
					if (toolCallChunks < MAX_TOOL_CALL_DETAIL_CHUNKS) {
						appendMd(partialToolCall.partialArguments());
					} else {
						appendMd(". ");
					}
					toolCallChunks++;
				})
				.onIntermediateResponse(chatResponse -> {
					// The model finished streaming this tool-calling round: every partial
					// callback for it has already been delivered. Reset so the next round
					// opens fresh sections instead of continuing stale ones.
					if (currentSection == Section.TOOL_CALL) {
						appendMd("\n```\n"); // close the last call's fenced block
					}
					closeSection();
					currentSection = Section.NONE;
					currentToolIndex = -1;
					// Stats recompute is deferred: tool results only land in chat memory after
					// every onToolExecuted has fired. recomputeStatsIfPending() picks it up at
					// the start of the next round.
				})
				.onToolExecuted(execution -> {
					msView.complete();
					appendMd("\n==Tool Result:==   *"+execution.request().id()+" : "+execution.request().name());
					if (execution.duration().toSeconds()>1)	appendMd("    took "+execution.duration().toSeconds()+" seconds*  \n");
					else appendMd("*\n");
					if (execution.hasFailed()) appendMd("`"+execution.result()+"`\n");
					msView.complete();
					statsRecomputePending = true;
				})
				.onCompleteResponse(response -> {
					currentSection = Section.NONE;
					currentToolIndex = -1;
//					log.info("'"+response.aiMessage().text()+"'");
//					log.info("finishReason="+response.metadata().finishReason()+",  "+response.metadata().tokenUsage());
					appendMd("\n\n- Turn complete. Tokens In: "+response.metadata().tokenUsage().inputTokenCount()+
							"   Tokens Out: "+response.metadata().tokenUsage().outputTokenCount()+
							"   Total: "+response.metadata().tokenUsage().totalTokenCount()+"\n");
					endTurn();
				})
				.onError(error -> {
					currentSection = Section.NONE;
					currentToolIndex = -1;
					error.printStackTrace();
					endTurn();
				})
				.start();
	}

	/**
	 * Recomputes streaming stats at the first streaming callback of a new round, once the
	 * previous round's tool results are actually in chat memory. No-op unless a tool round
	 * happened (see statsRecomputePending).
	 */
	private void recomputeStatsIfPending() {
		if (!statsRecomputePending) return;
		statsRecomputePending = false;
		inputTokens = tcEst.estimateTokenCountInMessages(chatMemory.messages());
		outputTokens = 0;
		streamStartNanos = System.nanoTime();
		lastLabelUpdateNanos = 0;
	}

	/**
	 * Closes the currently open section, if any: finalizes the in-progress markdown
	 * so the next section's header doesn't get absorbed into it.
	 */
	private void closeSection() {
		if (currentSection != Section.NONE) {
			msView.complete();
			appendMd("\n");
		}
	}

	private void endTurn() {
		msView.complete();
		Platform.runLater(() -> btnSend.setDisable(false));
	}

	private void appendMd(String md) {
		mdRaw.append(md);
		msView.append(md);
	}

	/**
	 * Updates the token/s and context-usage labels, throttled to at most every 200 ms.
	 * Called from streaming callbacks (non-FX thread).
	 */
	private void updateStatsLabels() {
		long now = System.nanoTime();
		if (now - lastLabelUpdateNanos < 200_000_000L) return; // 200 ms throttle
		lastLabelUpdateNanos = now;

		double elapsedSec = (now - streamStartNanos) / 1_000_000_000.0;
		double tokPerSec = elapsedSec > 0.2 ? outputTokens / elapsedSec : 0;
		int totalTokens = inputTokens + outputTokens;
		double pct = 100.0 * totalTokens / cfg.model_length;

		String ctxText = String.format("%d/%d (%.1f%%)", totalTokens, cfg.model_length, pct);
		String tpsText = String.format("%.1f tok/s", tokPerSec);

		Platform.runLater(() -> {
			lblCtxUsage.setText(ctxText);
			lblTokPerSec.setText(tpsText);
		});
	}

	private File askUserWhereToSave() {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setInitialDirectory(new File("/projects/AI/md/"));
		fileChooser.setTitle("Save Markdown File As:");
		fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Markdown Files", "*.md"));
		String dateTimeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		fileChooser.setInitialFileName(dateTimeStr+".md");
		return fileChooser.showSaveDialog(txaToSend.getScene().getWindow());
	}

	private File askUserWhatToLoad() {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setInitialDirectory(new File("/projects/AI/md/"));
		fileChooser.setTitle("Load Markdown File:");
		fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Markdown Files", "*.md"));
		return fileChooser.showOpenDialog(txaToSend.getScene().getWindow());
	}

	private void showRawMarkdown() throws IOException {
		GuiceFXMLLoader.Result res = fxmlLoader.load(getClass().getResource("RawMarkdownView.fxml"));
		RawMarkdownViewController ctlr = (RawMarkdownViewController) res.getController();
		ctlr.setText(mdRaw.toString());
		Scene scene = new Scene(res.getRoot());
		scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
		Stage stage = new Stage();
		stage.setTitle("Raw Markdown View");
		stage.setScene(scene);
		stage.show();
	}

	private String readResource(String name) {
		try (InputStream in = getClass().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalArgumentException("Missing resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
