package dev.turnfab;

import java.io.File;
import java.io.IOException;
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

	private Coder bot;
	private boolean firstThinkChunk = true;
	private String firstRespChunk = null;
	private boolean firstToolCallChunk = true;
	private int turnNumber = 0;
	private StringBuilder mdRaw = new StringBuilder();

	private boolean armCancel = false;

	private TokenCountEstimator tcEst = new OpenAiTokenCountEstimator("o200K_BASE"); // hack to trigger this.encoding = ENCODING_REGISTRY.getEncoding(O200K_BASE);

	@FXML
	private void initialize() {
		stpRenderedConvo.getChildren().add(msView.getView());
		tabPane.getSelectionModel().select(1);
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


		bot = AiServices.builder(Coder.class)
				.streamingChatModel(model)
				.systemMessageTransformer(systemMessage -> systemMessage + " Today's date is " + LocalDate.now() + ".")
				.toolProvider(toolManager.getProvider())
				.chatMemory(TokenWindowChatMemory.withMaxTokens(200000, tcEst))
				.build();

	}



	//////////////////////////////////////////////////////////

	@FXML
	public void onInitBot() {
		log.info("");
		initBot();
	}

	@FXML
	public void onInitMcp() {
		log.info("");
		toolManager.init();
		toolManager.printEnabledTools();
	}

	@FXML
	public void onBuildPrompt() {
		log.info("");
		txaPrompt.clear();
		txaPrompt.appendText("Documentation for markstream-vue:");
		txaPrompt.appendText(promptManager.msvDocs(toolManager.getMsvsrcMcpClient()));
		txaPrompt.appendText("Location of files I am working with:");
		txaPrompt.appendText(promptManager.mspLoc(toolManager.getMainprjMcpClient()));
		txaPrompt.appendText("How do I put a margin around my rendered markdown? I tried the obvious in markstream-view.html line 10 ");
		txaPrompt.appendText("and changed margin: 0 to margin: 1em - this almost worked but it didn't put a margin between the right ");
		txaPrompt.appendText("side of the rendered text. The scroll bar actually covers up part of the text and makes it hard to read.");
	}

	@FXML
	public void onSendPrompt() {
		log.info("");
		beginTurn(txaPrompt.getText(), "(PROMPT)");
		tabPane.getSelectionModel().select(1);
	}

	@FXML
	public void onTest() {
		log.info("");
	}

	//////////////////////////////////////////////////////////

	@FXML
	public void onSend() {
		beginTurn(txaToSend.getText(), null);
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


	private void beginTurn(String toSend, String summary) {
		if (bot==null) {
			log.warning("Bot has not been initialized!");
			return;
		}
		armCancel = false;
		++turnNumber;
		btnSend.setDisable(true);
		firstThinkChunk = true;
		firstRespChunk = null;
		firstToolCallChunk = true;

		appendMd("\n\n## ==Turn "+turnNumber+"==\n");
		appendMd("> ");
		if (summary==null) appendMd(toSend);
		else appendMd(summary);

		TokenStream stream = bot.chat(toSend,
				OpenAiChatRequestParameters.builder()
						.reasoningEffort("medium")
						.build());

		stream
				.onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext context) -> {
					if (firstRespChunk==null) {
						// the first response chunk is streamed BEFORE the last thinking chunk, so store it here
						firstRespChunk = partialResponse.text();
					} else if (!firstRespChunk.isEmpty()) {
						msView.complete();
						appendMd("\n\n");
						// and then on the next call append the stored first chunk
						appendMd(firstRespChunk.stripLeading());
						firstRespChunk = "";
						// and then the current chunk
						appendMd(partialResponse.text());
					} else {
						appendMd(partialResponse.text());
					}
					if (armCancel) {
                        context.streamingHandle().cancel();
						appendMd("\n\n**CANCELLED**\n\n");
						endTurn();
					}
				})
				.onPartialThinkingWithContext((PartialThinking partialThinking, PartialThinkingContext context) -> {
					if (firstThinkChunk) {
						firstThinkChunk = false;
						msView.complete();
						appendMd("\n\n> *Thinking:*  \n");
					}
					appendMd(partialThinking.text().replace("\n", "\n> "));
					if (armCancel) {
						context.streamingHandle().cancel();
						appendMd("\n\n**CANCELLED**\n\n");
						endTurn();
					}
				})
				.onPartialToolCall(partialToolCall -> {
					if (firstToolCallChunk) {
						firstToolCallChunk = false;
						firstThinkChunk = true;
						firstRespChunk = null;  //???
						appendMd("\n\n> **Tool Call:**   *"+partialToolCall.id()+" : "+partialToolCall.name()+"*`  \n");
					}
					appendMd(partialToolCall.partialArguments());
				})
				.onToolExecuted(execution -> {
					msView.complete();
					appendMd("`\n> **Tool Result:**   *"+execution.request().id()+" : "+execution.request().name()+"    took "+execution.duration().toSeconds()+" seconds*  \n");
					if (execution.hasFailed()) appendMd("`"+execution.result()+"`");
					msView.complete();
				})
				.onCompleteResponse(response -> {
//					log.info("'"+response.aiMessage().text()+"'");
//					log.info("finishReason="+response.metadata().finishReason()+",  "+response.metadata().tokenUsage());
					appendMd("\n\n- Turn complete. Tokens In: "+response.metadata().tokenUsage().inputTokenCount()+
							"   Tokens Out: "+response.metadata().tokenUsage().outputTokenCount()+
							"   Total: "+response.metadata().tokenUsage().totalTokenCount()+"\n");
					endTurn();
				})
				.onError(error -> {
					error.printStackTrace();
					endTurn();
				})
				.start();
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

}
