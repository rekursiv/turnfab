package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@FXMLController
public class SessionController {

	@Inject private Logger log;
	@Inject private SystemConfig sysCfg;
	@Inject private EventBus eb;

	@FXML private TableView<SessionData> tbvSessions;
	@FXML private TableColumn<SessionData, String> tbcSessionName;
	@FXML private TableColumn<SessionData, Integer> tbcTurns;
	@FXML private TableColumn<SessionData, Integer> tbcTokens;
	@FXML private TableColumn<SessionData, String> tbcUserPrompt;

	private final ConfigManager<SetupConfig> setupCfgMgr = ConfigManager.yaml(SetupConfig.class, null);
	private SessionData selectedSession;

	@FXML
	private void initialize() {
		tbvSessions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);

		tbcSessionName.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getName()));
		tbcTurns.setCellValueFactory(f -> new SimpleIntegerProperty(f.getValue().getTurns()).asObject());
		tbcTokens.setCellValueFactory(f -> new SimpleIntegerProperty(f.getValue().getTokens()).asObject());
		tbcUserPrompt.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getUserPrompt()));

		tbvSessions.getSelectionModel().selectedItemProperty().addListener(
				(_, _, newValue) -> onSelect(newValue));

		walkSessions();
	}

	@FXML
	public void onHistory() {
		if (selectedSession != null) {
			eb.post(new SessionLoadEvent(selectedSession.getName(), sysCfg.sessionPath, 0));
		}
	}

	@FXML
	public void onSetup() {
		if (selectedSession != null) {
			eb.post(new SessionSetupEvent(selectedSession.getName(), selectedSession.getSetupConfig(), false));
		}
	}

	@FXML
	public void onContinue() {
		if (selectedSession != null) {
			eb.post(new SessionSetupEvent(selectedSession.getName(), selectedSession.getSetupConfig(), true));
			eb.post(new SessionLoadEvent(selectedSession.getName(), sysCfg.sessionPath, selectedSession.getTurns()));
		}
	}

	private void onSelect(SessionData sessionData) {
		selectedSession = sessionData;
	}

	private void walkSessions() {
        File[] files = Path.of(sysCfg.sessionPath).toFile().listFiles();
        assert files != null;
        for (var sessionDir : files) {
			if (sessionDir.isDirectory()) {
				var sessionName = sessionDir.getName();
                SetupConfig setupConfig = null;
                try {
                    setupConfig = setupCfgMgr.loadFromFile(sysCfg.sessionPath+sessionName+"/setup.yaml");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
				if (setupConfig != null) tbvSessions.getItems().add(new SessionData(setupConfig, sessionName));
			}
		}
	}

	private record SessionData(SetupConfig setupConfig, String sessionName) {
		public String getName() {
			return sessionName;
		}
		public int getTurns() {
			return setupConfig.numTurns;
		}
		public int getTokens() {
			return setupConfig.numTokens;
		}
		public String getUserPrompt() {
			return FLATTEN.matcher(setupConfig.userPrompt==null?"":setupConfig.userPrompt).replaceAll(" ").trim();
		}
		public SetupConfig getSetupConfig() {
			return setupConfig;
		}
		private static final Pattern FLATTEN = Pattern.compile("\\s*\\R\\s*");
	}
}
