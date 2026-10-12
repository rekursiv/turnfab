package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.inject.Inject;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;

import java.util.logging.Logger;

@FXMLController
public class TextWindowController {

	@Inject private Logger log;

	@FXML private TextArea txaText;

	@FXML
	private void initialize() {
	}
	
	public void setText(String text) {
		txaText.setText(text);
	}
	
}
