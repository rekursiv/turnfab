package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import org.controlsfx.control.CheckListView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.logging.Level;
import java.util.logging.Logger;


@FXMLController
public class SetupController {
	@Inject	private Logger log;
	@Inject private SystemConfig cfg;
	@Inject private EventBus eb;
	@Inject private ToolManager toolManager;

	@FXML private CheckBox ckbThink;
	@FXML private CheckBox ckbLogReq;
	@FXML private CheckBox ckbLogRes;
	@FXML private ChoiceBox<String> chbSysPrompts;
	@FXML private TextArea txaSysPrompt;
	@FXML private TextArea txaUserPrompt;

	@FXML private CheckListView<String> clvMcpServers = new CheckListView<>();

	@FXML
	private void initialize() throws IOException {
    	ckbThink.setSelected(cfg.enableThinking);
		ckbLogReq.setSelected(cfg.logRequests);
		ckbLogRes.setSelected(cfg.logResponses);

		ObservableList<String> sysPromptFiles = FXCollections.observableArrayList();
		String sysPromptDir = System.getProperty("user.dir")+"/../turnfab_config/system_prompts/";
		Files.list(Paths.get(sysPromptDir)).forEach(file -> {
			System.out.println(file.getFileName());
			sysPromptFiles.add(file.getFileName().toString());
		});
		chbSysPrompts.itemsProperty().setValue(sysPromptFiles);
		chbSysPrompts.valueProperty().addListener(
				(_, _, newValue) -> onSelectSystemPrompt(newValue));
		chbSysPrompts.getSelectionModel().select(cfg.systemPromptFileName);

		ObservableList<String> mcpServers = FXCollections.observableArrayList();
		mcpServers.addAll(cfg.mcpServers);  //  FIXME
		clvMcpServers.itemsProperty().setValue(mcpServers);
		mcpServers.forEach(server -> clvMcpServers.getCheckModel().check(server));

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
	public void onInitMcp() {
		toolManager.init();
		toolManager.printEnabledTools();
	}

	@FXML
	public void onBuildPrompt() {
		txaUserPrompt.clear();
		txaUserPrompt.appendText(toolManager.getPrompt());
	}

	@FXML
	public void onSendPrompt() {
		eb.post(new SendPromptEvent(txaUserPrompt.getText()));
	}

	@FXML
	public void onClearPrompt() {
		txaUserPrompt.clear();
	}

	@FXML
	public void onTest() {

	}


	private void loadSystemPromptFromFile(String fileName) throws IOException {
		txaSysPrompt.clear();
		String pathStr = System.getProperty("user.dir")+"/../turnfab_config/system_prompts/"+fileName;
		log.info("Loading system prompt: "+pathStr);
		for (String line : Files.readAllLines(Paths.get(pathStr), StandardCharsets.UTF_8)) {
			txaSysPrompt.appendText(line);
		}
		txaSysPrompt.appendText("\nToday's date is " + LocalDate.now().format(TokenJockey.DATE_FORMATTER) + ".");
	}

}


