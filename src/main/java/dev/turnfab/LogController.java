package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;

import java.util.logging.Logger;


@FXMLController
public class LogController {
	@Inject	private Logger log;

	@FXML TextArea taLog;
	
	@FXML
	private void initialize() {

	}

	@Subscribe
	public void onLog(LogEvent evt) {
		Platform.runLater(()->taLog.appendText(evt.getMsg()));
	}
	
}


