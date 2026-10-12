package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
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
	@Inject private ConfigManager<SystemConfig> cfgMgr;
	@Inject private SystemConfig cfg;
	@Inject private EventBus eb;
	@Inject private ToolManager toolManager;

	@FXML private CheckBox ckbThink;
	@FXML private CheckBox ckbLogReq;
	@FXML private CheckBox ckbLogRes;
	@FXML private CheckBox ckbAddDate;
	@FXML private ChoiceBox<String> chbSysPrompts;
	@FXML private TextArea txaSysPrompt;
	@FXML private TextArea txaTcPreview;
	@FXML private TextArea txaUserPrompt;

	@FXML private CheckListView<String> clvMcpServers = new CheckListView<>();

	@FXML private ListView<String> lsvToolCtxAvail;
	@FXML private ListView<String> lsvToolCtxSel;

	private StringBuilder systemPrompt = new StringBuilder();

	@FXML
	private void initialize() throws IOException {
		toolManager.init();

    	ckbThink.setSelected(cfg.enableThinking);
		ckbLogReq.setSelected(cfg.logRequests);
		ckbLogRes.setSelected(cfg.logResponses);
		ckbAddDate.setSelected(cfg.addDateToSystemPrompt);

		chbSysPrompts.itemsProperty().setValue(FXCollections.observableArrayList());
		String sysPromptDir = System.getProperty("user.dir")+"/../turnfab_config/system_prompts/";
		Files.list(Paths.get(sysPromptDir)).forEach(file -> {
			chbSysPrompts.getItems().add(file.getFileName().toString());
		});
		chbSysPrompts.valueProperty().addListener(
				(_, _, newValue) -> onSelectSystemPrompt(newValue));
		chbSysPrompts.getSelectionModel().select(cfg.systemPromptFileName);

		clvMcpServers.itemsProperty().setValue(FXCollections.observableArrayList());
		clvMcpServers.getItems().addAll(toolManager.getMcpServerList());
		if (cfg.mcpServers!=null) {
			for (String enabled : cfg.mcpServers) {
				clvMcpServers.getCheckModel().check(enabled);
			}
		}

		lsvToolCtxAvail.itemsProperty().setValue(FXCollections.observableArrayList());
		lsvToolCtxAvail.getItems().addAll(toolManager.getToolContextList());
		// TODO: add selected listener to lsvToolCtxAvail that calls toolManager.getToolContextPreview(selected)

		lsvToolCtxSel.itemsProperty().setValue(FXCollections.observableArrayList());
		if (cfg.toolContext!=null) lsvToolCtxSel.getItems().addAll(cfg.toolContext);

	}

	private void onSelectSystemPrompt(String fileName){
		cfg.systemPromptFileName = fileName;
        try {
            loadSystemPromptFromFile(fileName);
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not load system prompt file " + fileName, e);
        }
    }

	@FXML
	public void onLoadTools() {
		writeGuiToCfg();
		toolManager.loadTools();
		toolManager.printEnabledTools();
	}

	@FXML
	public void onBuildPrompt() {
		txaUserPrompt.clear();
		txaUserPrompt.appendText(toolManager.getPrompt());
	}

	@FXML
	public void onSendPrompt() {
		eb.post(new BeginTurnEvent(txaSysPrompt.getText(), txaUserPrompt.getText(), null));
	}

	@FXML
	public void onClearPrompt() {
		txaUserPrompt.clear();
	}

	@FXML
	public void onSaveCfg() throws Exception {
		writeGuiToCfg();
		cfgMgr.save(cfg);
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
	public void onCkbAddDate(){
		cfg.addDateToSystemPrompt = ckbAddDate.isSelected();
		txaSysPrompt.clear();
		txaSysPrompt.appendText(systemPrompt.toString());
		if (ckbAddDate.isSelected()) {
			txaSysPrompt.appendText("\nToday's date is " + LocalDate.now().format(DATE_FORMATTER) + ".");
		}
	}

	private void writeGuiToCfg() {
		cfg.mcpServers = clvMcpServers.getCheckModel().getCheckedItems();
		cfg.toolContext = lsvToolCtxSel.getItems();
		cfg.enableThinking = ckbThink.isSelected();
		cfg.logRequests = ckbLogReq.isSelected();
		cfg.logResponses = ckbLogRes.isSelected();

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

}

