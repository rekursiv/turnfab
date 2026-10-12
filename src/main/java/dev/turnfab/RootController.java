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


	@FXML private TabPane tabPane;


	@FXML
	private void initialize() {
//		tabPane.getSelectionModel().select(1);
	}

	@Subscribe
	public void onPrompt(BeginTurnEvent evt) {
		if (evt.getSystemPrompt()!=null) tabPane.getSelectionModel().select(1);
	}

}
