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
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.controlsfx.control.CheckListView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

	@FXML private Button btnSendPrompt;
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

	@FXML private ListView<String> lsvToolCtxAvail;
	@FXML private ListView<String> lsvToolCtxSel;

	private ConfigManager<SetupConfig> setupCfgMgr;

	private StringBuilder systemPrompt = new StringBuilder();



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
		lsvToolCtxAvail.getItems().addAll(toolManager.getToolContextList());
		lsvToolCtxAvail.getSelectionModel().selectedItemProperty().addListener(
				(_, _, newValue) -> {
			if (newValue != null) txaToolPreview.setText(toolManager.getToolContextPreview(newValue));
		});

		lsvToolCtxSel.itemsProperty().setValue(FXCollections.observableArrayList());

		setupCfgMgr = new ConfigManager<>(SetupConfig.class, "../turnfab_config/setups/default.yaml", new ObjectMapper(new YAMLFactory()));
		chbSetup.itemsProperty().setValue(FXCollections.observableArrayList());
		String setupDir = System.getProperty("user.dir")+"/../turnfab_config/setups/";
		Files.list(Paths.get(setupDir)).forEach(file -> {
			chbSetup.getItems().add(file.getFileName().toString());
		});
		chbSetup.valueProperty().addListener(
				(_, _, newValue) -> onSelectSetup(newValue));
		chbSetup.getSelectionModel().select("default.yaml");
	}



	@FXML
	public void onLoadTools() {
		writeGuiToSetup();
        try {
            toolManager.loadTools();
        } catch (Exception e) {
            txaToolPreview.setText(e.getMessage());
			return;
        }
		txaToolPreview.setText(toolManager.getEnabledTools());
		txaTcSummary.clear();
		txaTcSummary.appendText(toolManager.getPromptSummary());
		btnShowFullPrompt.setDisable(false);
		btnSendPrompt.setDisable(false);
		btnShowTools.setDisable(false);
	}

	@FXML
	public void onSaveSetup() throws Exception {
		writeGuiToSetup();
		if (ckbSaveAs.isSelected()) {
			setupCfgMgr.saveToFile("../turnfab_config/setups/"+txfSaveAs.getText()+".yaml", setupCfg);
			chbSetup.getItems().add(txfSaveAs.getText()+".yaml");
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
	public void onSendPrompt() {
		eb.post(new BeginTurnEvent(txaSysPrompt.getText(), toolManager.getPrompt()+txaUserPrompt.getText(),
				toolManager.getPromptSummary()+"\n"+txaUserPrompt.getText()));
	}

	@FXML
	public void onClearPrompt() {
		txaUserPrompt.clear();
	}

	@FXML
	public void onTcAdd() {
		String sel = lsvToolCtxAvail.getSelectionModel().getSelectedItem();
		if (sel!=null) lsvToolCtxSel.getItems().add(sel);
	}

	@FXML
	public void onTcRemove() {
		String sel = lsvToolCtxSel.getSelectionModel().getSelectedItem();
		if (sel!=null) lsvToolCtxSel.getItems().remove(sel);
	}

	@FXML
	public void onTcUp() {
		ObservableList<String> items = lsvToolCtxSel.getItems();
		int idx = lsvToolCtxSel.getSelectionModel().getSelectedIndex();
		if (idx > 0) {
			items.add(idx - 1, items.remove(idx));
			lsvToolCtxSel.getSelectionModel().select(idx - 1);
		}
	}

	@FXML
	public void onTcDown() {
		ObservableList<String> items = lsvToolCtxSel.getItems();
		int idx = lsvToolCtxSel.getSelectionModel().getSelectedIndex();
		if (idx >= 0 && idx < items.size() - 1) {
			items.add(idx + 1, items.remove(idx));
			lsvToolCtxSel.getSelectionModel().select(idx + 1);
		}
	}

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

	private void onSelectSetup(String fileName){
		log.info(fileName);
		try {
			setupCfg = setupCfgMgr.loadFromFile("../turnfab_config/setups/"+fileName);
		} catch (Exception e) {
			setupCfg = setupCfgMgr.load();  // load defaults if no file exists
		}
		writeSetupToGui();
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
		log.info("Writing setup to GUI: "+setupCfg.addDateToSystemPrompt);  /////

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
		txaUserPrompt.setText(setupCfg.userPrompt);
	}

	private void writeGuiToSetup() {
		setupCfg.mcpServers = clvMcpServers.getCheckModel().getCheckedItems();
		setupCfg.toolContext = lsvToolCtxSel.getItems();
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
