package dev.turnfab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

import com.cathive.fx.guice.FXMLController;
import com.cathive.fx.guice.GuiceFXMLLoader;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
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

	@Inject private Logger log;
	@Inject private SystemConfig cfg;
	@Inject private EventBus eb;
	@Inject private GuiceFXMLLoader fxmlLoader;

	@Inject private DynamicMcpToolProvider toolProvider;
	@Inject private ToolManager toolManager;
	@Inject private TokenJockey tokenJockey;

	@FXML private TabPane tabPane;
	@FXML private StackPane stpRenderedConvo;
	@FXML private TextArea txaPrompt;
	@FXML private TextArea txaToSend;
	@FXML private Button btnSend;

	@FXML private Label lblCtxUsage;
	@FXML private Label lblTokPerSec;

	private MarkstreamView msView = new MarkstreamView();
	private StringBuilder mdRaw = new StringBuilder();


	@FXML
	private void initialize() {
		stpRenderedConvo.getChildren().add(msView.getView());
//		tabPane.getSelectionModel().select(1);
	}


	@FXML
	public void onInitBot() {
		tokenJockey.initBot();
	}

	@FXML
	public void onInitMcp() {
		toolManager.init();
		toolManager.printEnabledTools();
	}

	@FXML
	public void onBuildPrompt() {
		txaPrompt.clear();
		txaPrompt.appendText(toolManager.getPrompt());
	}

	@FXML
	public void onSendPrompt() {
		btnSend.setDisable(true);
		tokenJockey.beginTurn(txaPrompt.getText());
		tabPane.getSelectionModel().select(1);
	}

	@FXML
	public void onTest() {
//		tokenJockey.testJournal();
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
		btnSend.setDisable(true);
		tokenJockey.beginTurn(txaToSend.getText());
		txaToSend.clear();
	}

	@FXML
	public void onSaveMd() throws IOException {
		File selectedFile = askUserWhereToSave();
		if (selectedFile!=null) {
			eb.post(new LogEvent("  Saving markdown to: "+selectedFile.getAbsolutePath()+"\n"));
			Files.writeString(selectedFile.toPath(), mdRaw.toString());
		}
	}

	@FXML
	public void onLoadMd() throws IOException {
		File selectedFile = askUserWhatToLoad();
		eb.post(new LogEvent("  Loading "+selectedFile.getAbsolutePath()+"\n"));
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
		tokenJockey.armCancel();
	}

	@Subscribe
	public void onTokenStats(TokenStatsEvent evt) {
		String ctxText = String.format("%d/%d (%.1f%%)", evt.getUsedTokens(), evt.getTotalTokens(), evt.getPercentTokensUsed());
		String tpsText = String.format("%.1f tok/s", evt.getTokensPerSec());
    	lblCtxUsage.setText(ctxText);
		lblTokPerSec.setText(tpsText);
	}

	@Subscribe
	public void onFlushStream(TokenFlushEvent evt) {
		msView.complete();
		if (evt.isEndOfTurn()) Platform.runLater(() -> btnSend.setDisable(false));
	}

	@Subscribe
	public void onSendStream(TokenStreamEvent evt) {
		mdRaw.append(evt.getChunk());
		msView.append(evt.getChunk());
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
