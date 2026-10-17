package dev.turnfab;

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
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;


@FXMLController
public class ChatController {

	private static final int PROMPT_MAX_ROW_COUNT = 12;

	@Inject private Logger log;
	@Inject private SystemConfig cfg;
	@Inject private EventBus eb;
	@Inject private GuiceFXMLLoader fxmlLoader;

	@Inject private DynamicMcpToolProvider toolProvider;
	@Inject private TokenJockey tokenJockey;

	@FXML private StackPane stpRenderedConvo;
	@FXML private TextArea txaToSend;
	@FXML private Button btnSend;

	@FXML private Label lblCtxUsage;
	@FXML private Label lblTokPerSec;

	private MarkstreamView msView = new MarkstreamView();
	private StringBuilder mdRaw = new StringBuilder();

	private boolean sendArmed = false;
	private int promptRowCount = 1;

	@FXML
	private void initialize() {
		stpRenderedConvo.getChildren().add(msView.getView());
	}

	//////////////////////////////////////////////////////////

	@FXML
	public void onSend() {
        sendPrompt();
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

	@FXML
	public void onPromptKey(KeyEvent evt) {
		if (evt.getCode() == KeyCode.ENTER) {
			expandPromptAreaHeight();
			int lastMinusOne = txaToSend.getText().length()-2;
			if (txaToSend.getText().lastIndexOf('\n', lastMinusOne) == lastMinusOne) {
				if (sendArmed) {
					sendArmed = false;
					txaToSend.setStyle("");
					sendPrompt();
				} else {
					sendArmed = true;
					txaToSend.setStyle("-fx-control-inner-background: #E0FAE0;");
				}
			}
		} else {
			sendArmed = false;
			txaToSend.setStyle("");
		}
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

	private void sendPrompt() {
//		btnSend.setDisable(true);
//		eb.post(new BeginTurnEvent(txaToSend.getText().strip()));
		System.out.println("*****");
		txaToSend.clear();
		resetPromptAreaHeight();
	}

	private void resetPromptAreaHeight() {
		promptRowCount=1;
		txaToSend.setPrefRowCount(promptRowCount);
	}

	private void expandPromptAreaHeight() {
		if (promptRowCount < PROMPT_MAX_ROW_COUNT) {
			promptRowCount++;
			txaToSend.setPrefRowCount(promptRowCount);
		}
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
		GuiceFXMLLoader.Result res = fxmlLoader.load(getClass().getResource("TextWindow.fxml"));
		TextWindowController ctlr = (TextWindowController) res.getController();
		ctlr.setText(mdRaw.toString());
		Scene scene = new Scene(res.getRoot());
		scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
		Stage stage = new Stage();
		stage.setTitle("Raw Markdown View");
		stage.setScene(scene);
		stage.show();
	}


}
