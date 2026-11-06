package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.cathive.fx.guice.GuiceFXMLLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.controlsfx.control.CheckListView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


@FXMLController
public class SetupController {
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");

	@Inject	private Logger log;
	@Inject private GuiceFXMLLoader fxmlLoader;
	@Inject private SetupConfig setupCfg;
	@Inject private EventBus eb;
	@Inject private ToolManager toolManager;

	@FXML private Button btnShowFullPrompt;
	@FXML private Button btnShowTools;
	@FXML private CheckBox ckbSaveAs;
	@FXML private TextField txfSaveAs;
	@FXML private CheckBox ckbThink;
	@FXML private CheckBox ckbLogReq;
	@FXML private CheckBox ckbLogRes;
	@FXML private CheckBox ckbAddDate;
	@FXML private ChoiceBox<String> chbSetup;
	@FXML private ChoiceBox<String> chbSysPrompts;
	@FXML private TextArea txaSysPrompt;
	@FXML private TextArea txaToolPreview;
	@FXML private TextArea txaTcSummary;
	@FXML private TextArea txaUserPrompt;

	@FXML private CheckListView<String> clvMcpServers = new CheckListView<>();

	@FXML private TitledPane tpToolPreview;
	@FXML private ListView<String> lsvToolCtxAvail;
	@FXML private ListView<String> lsvToolCtxSel;

	/** The two-pixel line that marks the row boundary a drag would drop into; sits over the ordered list. */
	@FXML private Rectangle rectInsertion;

	/** Only ever set mid-drag, and only for a drag that started in one of the two tool context lists. */
	private String draggedName;
	private ListView<String> draggedFrom;
	private int draggedIntoIndex;

	private ConfigManager<SetupConfig> setupCfgMgr;

	private StringBuilder systemPrompt = new StringBuilder();

	private boolean loadSetupArmed = true;
	private boolean toolsLoaded = false;

	@FXML
	private void initialize() throws IOException {
		toolManager.init();

		chbSysPrompts.itemsProperty().setValue(FXCollections.observableArrayList());
		String sysPromptDir = System.getProperty("user.dir")+"/../turnfab_config/system_prompts/";
		Files.list(Paths.get(sysPromptDir)).forEach(file -> {
			chbSysPrompts.getItems().add(file.getFileName().toString());
		});
		chbSysPrompts.valueProperty().addListener(
				(_, _, newValue) -> onSelectSystemPrompt(newValue));

		clvMcpServers.itemsProperty().setValue(FXCollections.observableArrayList());
		clvMcpServers.getItems().addAll(toolManager.getMcpServerList());

		lsvToolCtxAvail.itemsProperty().setValue(FXCollections.observableArrayList());
		lsvToolCtxSel.itemsProperty().setValue(FXCollections.observableArrayList());
		syncAvailable();

		lsvToolCtxAvail.getSelectionModel().selectedItemProperty().addListener(
				(_, _, newValue) -> onToolContextSelect(newValue));
		lsvToolCtxAvail.focusedProperty().addListener(
				(obs, oldVal, newVal) -> {
					if (newVal) onToolContextSelect(lsvToolCtxAvail.getSelectionModel().getSelectedItem());
				});
		lsvToolCtxSel.getSelectionModel().selectedItemProperty().addListener(
				(_, _, newValue) -> onToolContextSelect(newValue));
		lsvToolCtxSel.focusedProperty().addListener(
				(obs, oldVal, newVal) -> {
					if (newVal) onToolContextSelect(lsvToolCtxSel.getSelectionModel().getSelectedItem());
				});

		installToolContextTransfer();

		setupCfgMgr = new ConfigManager<>(SetupConfig.class, "../turnfab_config/setups/default.yaml", new ObjectMapper(new YAMLFactory()));
		chbSetup.itemsProperty().setValue(FXCollections.observableArrayList());
		String setupDir = System.getProperty("user.dir")+"/../turnfab_config/setups/";
		Files.list(Paths.get(setupDir)).forEach(file -> {
			chbSetup.getItems().add(file.getFileName().toString());
		});
		chbSetup.valueProperty().addListener(
				(_, _, newValue) -> onSelectSetup(newValue));
		chbSetup.getSelectionModel().select("default.yaml");
		hideMarker();
	}



	@FXML
	public void onLoadTools() {
		loadTools();
	}

	@FXML
	public void onSaveSetup() throws Exception {
		writeGuiToSetup();
		if (ckbSaveAs.isSelected()) {
			setupCfgMgr.saveToFile("../turnfab_config/setups/"+txfSaveAs.getText()+".yaml", setupCfg);
			chbSetup.getItems().add(txfSaveAs.getText()+".yaml");

			// we want the file we just saved to appear in the ChoiceBox,
			// but there is no need to actually re-load the file
			loadSetupArmed = false;
			chbSetup.getSelectionModel().select(txfSaveAs.getText()+".yaml");
			loadSetupArmed = true;
		} else {
			setupCfgMgr.saveToFile("../turnfab_config/setups/"+chbSetup.getValue(), setupCfg);
		}
	}

	@FXML
	public void onSaveAs() {
		if (ckbSaveAs.isSelected()) {
			txfSaveAs.setDisable(false);
		} else {
			txfSaveAs.setDisable(true);
		}
	}

	@FXML
	public void onShowFullPrompt() throws IOException {
		loadFullPromptWindow();
	}

	@FXML
	public void onShowTools() throws IOException {
		loadToolViewWindow();
	}

	@FXML
	public void onBeginChat() {
		if (!toolsLoaded) {
			toolsLoaded = loadTools();
		}
		if (toolsLoaded) {
			eb.post(new BeginTurnEvent(txaSysPrompt.getText(), toolManager.getPrompt()+txaUserPrompt.getText(),
					toolManager.getPromptSummary()+"\n"+txaUserPrompt.getText()));
		}
	}

	@FXML
	public void onClearPrompt() {
		txaUserPrompt.clear();
	}

	private void onToolContextSelect(String name) {
		if (name==null) {
			tpToolPreview.setText("Tool Preview");
			txaToolPreview.setText("");
		} else {
			tpToolPreview.setText("Tool Preview: "+name);
			txaToolPreview.setText(toolManager.getToolContextPreview(name));
		}
	}

	/**
	 * Tool context transfer.
	 *
	 * The two lists used to trade contexts through four buttons. Double-click does the trading now, and a
	 * drag does it too, with one extra promise: in the ordered list, the drop position is the position the
	 * context gets built into the prompt at.
	 */
	private void installToolContextTransfer() {
		lsvToolCtxAvail.setOnMouseClicked(e -> {
			if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
				moveIntoSelected(lsvToolCtxAvail.getSelectionModel().getSelectedItem(), lsvToolCtxSel.getItems().size());
			}
		});
		lsvToolCtxSel.setOnMouseClicked(e -> {
			if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
				moveIntoAvailable(lsvToolCtxSel.getSelectionModel().getSelectedItem());
			}
		});

		for (ListView<String> list : List.of(lsvToolCtxAvail, lsvToolCtxSel)) {
			list.setOnDragDetected(e -> beginDrag(list, e));
			list.setOnDragOver(e -> dragHovered(list, e));
			list.setOnDragDropped(e -> dropInto(list, e));
			list.setOnDragExited(e -> hideMarker());
			list.setOnDragDone(e -> endDrag());
		}
	}

	/** Picks the row under the pointer, whether or not it happens to be the selected one. */
	private void beginDrag(ListView<String> list, MouseEvent e) {
		String name = nameUnderCursor(list, e.getSceneX(), e.getSceneY());
		if (name == null) return;
		draggedName = name;
		draggedFrom = list;
		Dragboard db = list.startDragAndDrop(TransferMode.MOVE);
		if (db == null) {
			endDrag();
			return;
		}
		ClipboardContent content = new ClipboardContent();
		content.put(DataFormat.PLAIN_TEXT, name);
		db.setContent(content);
		e.consume();
	}

	private void dragHovered(ListView<String> list, DragEvent e) {
		if (draggedName == null) return;                            // somebody else's drag, most likely dragged text
		if (list == draggedFrom && list == lsvToolCtxAvail) return; // reordering the alphabetical column buys nothing
		DropTarget target = dropTargetAt(list, e.getSceneY());
		draggedIntoIndex = target.index();
		if (list == lsvToolCtxSel) showMarker(target);
		else hideMarker();
		e.acceptTransferModes(TransferMode.MOVE);
		e.consume();
	}

	private void dropInto(ListView<String> list, DragEvent e) {
		boolean moved = draggedName != null && (list == lsvToolCtxSel || list != draggedFrom);
		if (moved) {
			if (list == lsvToolCtxSel) moveIntoSelected(draggedName, draggedIntoIndex);
			else moveIntoAvailable(draggedName);
		}
		e.setDropCompleted(moved);
		e.consume();
		endDrag();
	}

	private void endDrag() {
		draggedName = null;
		draggedFrom = null;
		hideMarker();
	}

	/** Into the ordered list, at the spot given; a position past the end simply appends. */
	private void moveIntoSelected(String name, int index) {
		if (name == null) return;
		ObservableList<String> sel = lsvToolCtxSel.getItems();
		int from = sel.indexOf(name);
		if (from >= 0 && from < index) index--;   // lifting it out of above slides the rest up
		sel.remove(name);
		index = Math.max(0, Math.min(index, sel.size()));
		sel.add(index, name);
		lsvToolCtxSel.getSelectionModel().select(index);
		syncAvailable();
	}

	/** Out of the ordered list and back among the choices, where its position means nothing. */
	private void moveIntoAvailable(String name) {
		if (name == null) return;
		lsvToolCtxSel.getItems().remove(name);
		syncAvailable();
	}

	/**
	 * What is left to choose from: every context the tool config defines, less the ones already picked.
	 * Nothing in this list is read in order, so it is sorted.
	 */
	private void syncAvailable() {
		List<String> avail = new ArrayList<>(toolManager.getToolContextList());
		avail.removeAll(lsvToolCtxSel.getItems());
		Collections.sort(avail);
		lsvToolCtxAvail.getItems().setAll(avail);
	}

	private String nameUnderCursor(ListView<String> list, double sceneX, double sceneY) {
		for (Node row : visibleRows(list)) {
			Bounds b = row.localToScene(row.getBoundsInLocal());
			if (!b.contains(sceneX, sceneY)) continue;
			int idx = ((IndexedCell<?>) row).getIndex();
			return (idx >= 0) ? list.getItems().get(idx) : null;
		}
		return null;
	}

	/** The rows on screen right now, top to bottom. Empty placeholder cells are not rows. */
	private List<Node> visibleRows(ListView<String> list) {
		List<Node> rows = new ArrayList<>();
		for (Node n : list.lookupAll(".list-cell")) {
			if (n instanceof IndexedCell<?> cell && !cell.isEmpty() && cell.getIndex() >= 0) rows.add(n);
		}
		rows.sort(Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinY()));
		return rows;
	}

	/**
	 * The row boundary the pointer sits closest to: the position to insert at, and the row edge the marker
	 * belongs on. Measured off the rows themselves, since that is the one reference that neither the list's
	 * own insets nor the container around it can shift.
	 */
	private DropTarget dropTargetAt(ListView<String> list, double sceneY) {
		List<Node> rows = visibleRows(list);
		if (rows.isEmpty()) {
			Bounds area = list.localToScene(list.getBoundsInLocal());
			return new DropTarget(0, area.getMinX(), area.getMinY(), area.getWidth());
		}
		for (Node row : rows) {
			Bounds b = row.localToScene(row.getBoundsInLocal());
			int idx = ((IndexedCell<?>) row).getIndex();
			if (sceneY <= b.getMinY()) return edge(idx, b, false);
			if (sceneY > b.getMaxY()) continue;
			boolean below = sceneY > b.getMinY() + b.getHeight() / 2;
			return edge(below ? idx + 1 : idx, b, below);
		}
		Node last = rows.get(rows.size() - 1);
		Bounds b = last.localToScene(last.getBoundsInLocal());
		return edge(((IndexedCell<?>) last).getIndex() + 1, b, true);
	}

	/** One row wide, resting on one of that row's edges: where a drop at that edge belongs. */
	private static DropTarget edge(int index, Bounds row, boolean below) {
		return new DropTarget(index, row.getMinX(), below ? row.getMaxY() : row.getMinY(), row.getWidth());
	}

	/**
	 * Parks the marker on the boundary. Transformed through the marker's own parent instead of through the
	 * ListView, so the only thing this has to believe is that a row is where that row's own bounds say it
	 * is; the list's insets, its scrollbar gutter and the titled pane wrapped around it all cancel out.
	 */
	private void showMarker(DropTarget target) {
		Node overlay = rectInsertion.getParent();
		Point2D p = overlay.sceneToLocal(target.lineX(), target.lineY());
		rectInsertion.setX(p.getX());
		rectInsertion.setY(p.getY());
		rectInsertion.setWidth(target.lineWidth());
		rectInsertion.setVisible(true);
	}

	private void hideMarker() {
		rectInsertion.setVisible(false);
	}

	/** Where a hover says the drop belongs: the position to insert at, and the line to draw it on. */
	private record DropTarget(int index, double lineX, double lineY, double lineWidth) { }

	@FXML
	public void onCkbThink(){
		setupCfg.enableThinking = ckbThink.isSelected();
		log.info("Thinking enabled: " + setupCfg.enableThinking);
	}

	@FXML
	public void onCkbAddDate() {
		txaSysPrompt.clear();
		txaSysPrompt.appendText(systemPrompt.toString());
		if (ckbAddDate.isSelected()) {
			txaSysPrompt.appendText("\nToday's date is " + LocalDate.now().format(DATE_FORMATTER) + ".");
		}
	}

	private boolean loadTools() {
		writeGuiToSetup();
		try {
			toolManager.loadTools();
		} catch (Exception e) {
			txaToolPreview.setText(e.getMessage());
			tpToolPreview.setText("Tool Preview: ERROR");
			return false;
		}
		txaToolPreview.setText(toolManager.getEnabledTools());
		tpToolPreview.setText("Tool Preview: Enabled Tools");
		txaTcSummary.clear();
		txaTcSummary.appendText(toolManager.getPromptSummary());
		btnShowFullPrompt.setDisable(false);
		btnShowTools.setDisable(false);
		return true;
	}

	private void onSelectSetup(String fileName){
//		log.info("Setup file selected: "+fileName);
		if (loadSetupArmed) {
			try {
				// copyInto rather than loadInto: the source instance is built in full before
				// setupCfg is touched, so a malformed file can't leave it half-updated
				setupCfgMgr.copyInto(setupCfgMgr.loadFromFile("../turnfab_config/setups/"+fileName), setupCfg);
//				log.info("Setup file loaded: "+fileName);
			} catch (Exception e) {
				try {
					// load() also creates the missing file with defaults, which is what we want here
					setupCfgMgr.copyInto(setupCfgMgr.load(), setupCfg);
				} catch (Exception e2) {
					log.log(Level.WARNING, "Could not load default setup", e2);
				}
//				log.info("Default setup loaded");
			}
			writeSetupToGui();
			toolsLoaded = false;
		}
	}

	private void onSelectSystemPrompt(String fileName){
		setupCfg.systemPromptFileName = fileName;
		try {
			loadSystemPromptFromFile(fileName);
		} catch (IOException e) {
			log.log(Level.WARNING, "Could not load system prompt file " + fileName, e);
		}
	}

	private void writeSetupToGui() {
		ckbAddDate.setSelected(setupCfg.addDateToSystemPrompt);
		chbSysPrompts.getSelectionModel().select(setupCfg.systemPromptFileName);
		ckbThink.setSelected(setupCfg.enableThinking);
		ckbLogReq.setSelected(setupCfg.logRequests);
		ckbLogRes.setSelected(setupCfg.logResponses);

		clvMcpServers.getCheckModel().clearChecks();
		if (setupCfg.mcpServers!=null) {
			for (String enabled : setupCfg.mcpServers) {
				clvMcpServers.getCheckModel().check(enabled);
			}
		}

		lsvToolCtxSel.getItems().clear();
		if (setupCfg.toolContext!=null) lsvToolCtxSel.getItems().addAll(setupCfg.toolContext);
		syncAvailable();
		txaUserPrompt.setText(setupCfg.userPrompt);
	}

	private void writeGuiToSetup() {
		setupCfg.mcpServers = clvMcpServers.getCheckModel().getCheckedItems();
		// a snapshot, not the live list: writeSetupToGui() clears that list, and would be clearing it through
		// this reference
		setupCfg.toolContext = new ArrayList<>(lsvToolCtxSel.getItems());
		setupCfg.logRequests = ckbLogReq.isSelected();
		setupCfg.logResponses = ckbLogRes.isSelected();
		setupCfg.userPrompt = txaUserPrompt.getText();
		setupCfg.addDateToSystemPrompt = ckbAddDate.isSelected();
	}

	private void loadSystemPromptFromFile(String fileName) throws IOException {
		systemPrompt = new StringBuilder();
		String pathStr = System.getProperty("user.dir")+"/../turnfab_config/system_prompts/"+fileName;
		log.info("Loading system prompt: "+pathStr);
		for (String line : Files.readAllLines(Paths.get(pathStr), StandardCharsets.UTF_8)) {
			systemPrompt.append(line);
		}
		onCkbAddDate();
	}

	private void loadFullPromptWindow() throws IOException {
		GuiceFXMLLoader.Result res = fxmlLoader.load(getClass().getResource("TextWindow.fxml"));
		TextWindowController ctlr = (TextWindowController) res.getController();
		ctlr.setText(txaSysPrompt.getText()+"\n\n"+toolManager.getPrompt()+txaUserPrompt.getText());
		Scene scene = new Scene(res.getRoot());
		scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
		Stage stage = new Stage();
		stage.setTitle("Full Prompt View");
		stage.setScene(scene);
		stage.show();
	}

	private void loadToolViewWindow() throws IOException {
		GuiceFXMLLoader.Result res = fxmlLoader.load(getClass().getResource("ToolView.fxml"));
		Scene scene = new Scene(res.getRoot());
		scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
		Stage stage = new Stage();
		stage.setTitle("MCP Tool View");
		stage.setScene(scene);
		stage.show();
	}

}
