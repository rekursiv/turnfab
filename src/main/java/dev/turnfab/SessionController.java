package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
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

@FXMLController
public class SessionController {

	@Inject private Logger log;
	@Inject private SystemConfig sysCfg;

	@FXML private TableView<SessionData> tbvSessions;
	@FXML private TableColumn<SessionData, String> tbcSessionName;
	@FXML private TableColumn<SessionData, Integer> tbcTurns;
	@FXML private TableColumn<SessionData, Integer> tbcTokens;
	@FXML private TableColumn<SessionData, String> tbcUserPrompt;

	private ConfigManager<SetupConfig> setupCfgMgr = ConfigManager.yaml(SetupConfig.class, null);

	@FXML
	private void initialize() {
		tbvSessions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);

		tbcSessionName.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getName()));
		tbcTurns.setCellValueFactory(f -> new SimpleIntegerProperty(f.getValue().getTurns()).asObject());
		tbcTokens.setCellValueFactory(f -> new SimpleIntegerProperty(f.getValue().getTokens()).asObject());
		tbcUserPrompt.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getUserPrompt()));

		walkSessions();
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
			return setupConfig.userPrompt;
		}
	}
}
