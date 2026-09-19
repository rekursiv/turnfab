package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.inject.Inject;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;

import java.util.logging.Logger;

@FXMLController
public class RawMarkdownViewController {

	@Inject private Logger log;

	@FXML private TextArea txaRaw;

	@FXML
	private void initialize() {
		log.info("Controller loaded");
	}
	
	public void setText(String text) {
		txaRaw.setText(text);
	}
	
}
