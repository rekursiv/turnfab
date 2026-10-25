package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.cathive.fx.guice.GuiceFXMLLoader;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
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
import java.util.logging.Level;
import java.util.logging.Logger;


@FXMLController
public class ChatController {

	private static final int PROMPT_MAX_ROW_COUNT = 12;
	private static final int SIZING_PULSE_BUDGET = 8;

	@Inject private Logger log;
	@Inject private SystemConfig cfg;
	@Inject private EventBus eb;
	@Inject private GuiceFXMLLoader fxmlLoader;
	@Inject private MarkstreamView msView;

	@Inject private DynamicMcpToolProvider toolProvider;
	@Inject private TokenJockey tokenJockey;

	@FXML private StackPane stpRenderedConvo;
	@FXML private TextArea txaToSend;
	@FXML private Button btnSend;

	@FXML private Label lblCtxUsage;
	@FXML private Label lblTokPerSec;

	private StringBuilder mdRaw = new StringBuilder();

	private boolean sendArmed = false;
	private boolean needsSizing = false;
	private int sizingPulses = 0;
	private Scene watchedScene = null;
	private final Runnable pulseHook = this::growIfOverflowing;

	@FXML
	private void initialize() {
		stpRenderedConvo.getChildren().add(msView.getView());

		txaToSend.textProperty().addListener((obs, was, is) -> requestGrow());
		txaToSend.widthProperty().addListener((obs, was, is) -> requestGrow());
		txaToSend.fontProperty().addListener((obs, was, is) -> requestGrow());
		txaToSend.skinProperty().addListener((obs, was, is) -> requestGrow());
	}

	//////////////////////////////////////////////////////////

	@FXML
	public void onSend() {
        sendPrompt();
	}

	@FXML
	public void onSaveMd() throws IOException {
		File selectedFile = askUserWhereToSave("Save Markdown File As:");
		if (selectedFile!=null) {
			eb.post(new LogEvent("  Saving markdown to: "+selectedFile.getAbsolutePath()+"\n"));
			Files.writeString(selectedFile.toPath(), mdRaw.toString());
		}
	}

	/**
	 * Transcript plus the ChatMemory that would be needed to carry on from here, in one file. Saving is
	 * safe mid-turn -- worst case the tail is an unfinished tool call, which is trimmed on the way back
	 * in -- but it is between turns that the snapshot is the most honest.
	 */
	@FXML
	public void onSaveSession() {
		File selectedFile = askUserWhereToSave("Save Session As:");
		if (selectedFile==null) return;
		eb.post(new LogEvent("  Saving session to: "+selectedFile.getAbsolutePath()+"\n"));
		try {
			SessionFile.save(selectedFile.toPath(), tokenJockey.currentSession(mdRaw.toString()));
		} catch (IOException e) {
			// The save verifies what it wrote, so this is where a file that would load back wrong ends up.
			log.log(Level.SEVERE, "Session save failed", e);
			eb.post(new LogEvent("  !! Session save failed: "+e.getMessage()+"\n"));
		}
	}

	/**
	 * Loads either flavour of file: a session file restores the memory and shows its transcript, a plain
	 * markdown file is view-only, exactly as it always was.
	 */
	@FXML
	public void onLoadMd() throws IOException {
		if (btnSend.isDisabled()) {
			eb.post(new LogEvent("  A turn is still running -- load between turns.\n"));
			return;
		}
		File selectedFile = askUserWhatToLoad();
		if (selectedFile==null) return;
		eb.post(new LogEvent("  Loading "+selectedFile.getAbsolutePath()+"\n"));

		SessionFile.Session loaded;
		try {
			loaded = SessionFile.load(selectedFile.toPath()).orElse(null);
		} catch (IOException e) {
			log.log(Level.SEVERE, "Session load failed", e);
			eb.post(new LogEvent("  !! Load failed: "+e.getMessage()+"\n"));
			return;
		}
		if (loaded != null) {
			tokenJockey.loadSession(loaded);
			showMarkdown(loaded.markdown());
		} else {
			showMarkdown(Files.readString(selectedFile.toPath()));
		}
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
		btnSend.setDisable(true);
		eb.post(new BeginTurnEvent(txaToSend.getText().strip()));
//		System.out.println("*****");
		txaToSend.clear();
		resetPromptAreaHeight();
	}

	private void resetPromptAreaHeight() {
		txaToSend.setPrefRowCount(1);
	}

	//////////////////////////////////////////////////////////

	/**
	 * Marks the prompt as needing a size check and asks to be consulted after the next layout
	 * pass. Nothing is measured here, because geometry lies less once the pulse is over.
	 */
	private void requestGrow() {
		needsSizing = true;
		sizingPulses = SIZING_PULSE_BUDGET;
		Scene sc = txaToSend.getScene();
		if (sc == null || sc == watchedScene) return;
		if (watchedScene != null) watchedScene.removePostLayoutPulseListener(pulseHook);
		sc.addPostLayoutPulseListener(pulseHook);
		watchedScene = sc;
	}

	/**
	 * The pulse's layout pass is done, so the geometry is finally honest. Grow the prompt to the
	 * row count its text needs, staying armed so the next pass verifies the answer -- and disarm
	 * once the text fits, hits the ceiling, or the pulse budget runs out.
	 */
	private void growIfOverflowing() {
		if (!needsSizing) return;
		if (--sizingPulses < 0) { needsSizing = false; return; }

		int rows = txaToSend.getPrefRowCount();
		double fit = promptFit();
		if (Double.isNaN(fit) || fit >= 1.0 || rows >= PROMPT_MAX_ROW_COUNT) { needsSizing = false; return; }

		int needed = Math.min(PROMPT_MAX_ROW_COUNT, Math.max(rows + 1, (int) Math.ceil(rows / fit)));
		txaToSend.setPrefRowCount(needed);
	}

	/**
	 * Fraction of the prompt's text that fits its current height: 1.0 when all of it shows, less when
	 * some is hidden below. The skin clips one content node inside the TextArea's scroll viewport,
	 * so that node's height is the whole text and the viewport's height is all that's visible.
	 * NaN when we can't see both, which leaves the prompt at whatever size it is.
	 */
	private double promptFit() {
		if (!(txaToSend.lookup(".viewport") instanceof Parent vp) || vp.getChildrenUnmodifiable().isEmpty()) {
			return Double.NaN;
		}
		double avail = vp.getBoundsInLocal().getHeight();
		double need = vp.getChildrenUnmodifiable().get(0).getBoundsInLocal().getHeight();
		return (avail > 0 && need > 0) ? avail / need : Double.NaN;
	}

	private File askUserWhereToSave(String title) {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setInitialDirectory(new File("/projects/AI/md/"));
		fileChooser.setTitle(title);
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

	/** Makes the transcript the contents of the pane, and {@code mdRaw} its mirror. */
	private void showMarkdown(String content) {
		msView.reset();
		msView.load(content);
		mdRaw = new StringBuilder(content);
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
