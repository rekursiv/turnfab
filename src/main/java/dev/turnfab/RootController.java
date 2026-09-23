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
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;


@FXMLController
public class RootController {

	private static final boolean DEBUG_CHAT_MODEL = false;

	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");

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



	private MarkstreamView msView = new MarkstreamView();

	private StringBuilder systemPrompt = new StringBuilder();
	private Bot bot;
	private Section currentSection = Section.NONE;
	private int currentToolIndex = -1;
	private int turnNumber = 0;
	private StringBuilder mdRaw = new StringBuilder();

	private boolean armCancel = false;

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
				.chatMemory(TokenWindowChatMemory.withMaxTokens(200000, tcEst))
				.build();

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
	}

	private void buildLc4jPrompt() {
		txaPrompt.clear();
		txaPrompt.appendText("Full searchable source code for LangChain4j is available with langchain4j_src-* tools.\n\n");
		txaPrompt.appendText("LangChain4j documentation: ");
		txaPrompt.appendText(promptManager.listDirTree(toolManager.getLc4jMcpClient(), "docs/docs", 3));
		txaPrompt.appendText(promptManager.readFile(toolManager.getMainprjMcpClient(),
//				"src/main/java/dev/turnfab/RootController.java"));
//				"src/main/java/dev/turnfab/PromptManager.java"));
				"src/main/java/dev/turnfab/ToolManager.java"));
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
		beginSession(txaPrompt.getText());
		tabPane.getSelectionModel().select(1);
	}

	@FXML
	public void onTest() {
		txaPrompt.clear();
		txaPrompt.appendText(promptManager.getTabs(toolManager.getMainprjMcpClient()));
	}

	//////////////////////////////////////////////////////////

	@FXML
	public void onSend() {
		beginSession(txaToSend.getText());
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


	private void beginSession(String initialPrompt) {
		buildSystemPrompt();
		appendMd("### ==System Prompt:==\n");
		appendMd(systemPrompt.toString());
		beginTurn(initialPrompt);
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

		appendMd("\n\n## ==Turn "+turnNumber+"==\n");
//		appendMd("> ");
		if (toSend.length()>900) appendMd("..."+toSend.substring(toSend.length()-900, toSend.length()));
		else appendMd(toSend);

		TokenStream stream = bot.chat(toSend,
				OpenAiChatRequestParameters.builder()
						.reasoningEffort("medium")
						.build());

		stream
				.onPartialThinkingWithContext((PartialThinking partialThinking, PartialThinkingContext context) -> {
					if (currentSection != Section.THINKING) {
						closeSection();
						appendMd("\n\n==Thinking:==\n");
						currentSection = Section.THINKING;
					}
					appendMd(partialThinking.text());
					if (armCancel) {
						context.streamingHandle().cancel();
						appendMd("\n\n==CANCELLED==\n\n");
						endTurn();
					}
				})
				.onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext context) -> {
					if (currentSection != Section.RESPONSE) {
						closeSection();
						appendMd("\n\n==Response:==\n");
						currentSection = Section.RESPONSE;
					}
					appendMd(partialResponse.text());
					if (armCancel) {
						context.streamingHandle().cancel();
						appendMd("\n\n==CANCELLED==\n\n");
						endTurn();
					}
				})
				.onPartialToolCall(partialToolCall -> {
					if (currentSection != Section.TOOL_CALL
							|| partialToolCall.index() != currentToolIndex) {
						closeSection();
						appendMd("\n\n==Tool Call:==   *"+partialToolCall.id()+" : "+partialToolCall.name()+"*`  \n");
						currentSection = Section.TOOL_CALL;
						currentToolIndex = partialToolCall.index();
					}
					appendMd(partialToolCall.partialArguments());
				})
				.onIntermediateResponse(chatResponse -> {
					// The model finished streaming this tool-calling round: every partial
					// callback for it has already been delivered. Reset so the next round
					// opens fresh sections instead of continuing stale ones.
					closeSection();
					currentSection = Section.NONE;
					currentToolIndex = -1;
				})
				.onToolExecuted(execution -> {
					msView.complete();
					appendMd("`\n==Tool Result:==   *"+execution.request().id()+" : "+execution.request().name()+"    took "+execution.duration().toSeconds()+" seconds*  \n");
					if (execution.hasFailed()) appendMd("`"+execution.result()+"`");
					msView.complete();
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
