package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.inject.Inject;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;

import java.util.logging.Logger;

@FXMLController
public class ToolViewController {

	@Inject private Logger log;
	@Inject private DynamicMcpToolProvider toolProvider;

	@FXML private ListView<ToolWrapper> lsvTools;
	@FXML private TextArea txaDesc;
	@FXML private TextArea txaParams;

	@FXML
	private void initialize() {
		lsvTools.itemsProperty().setValue(FXCollections.observableArrayList());
		ToolProviderResult tpr = toolProvider.provideTools(null);
		for (AiServiceTool tool : tpr.aiServiceTools()) {
			lsvTools.getItems().add(new ToolWrapper(tool));
		}
		lsvTools.getSelectionModel().selectedItemProperty().addListener(
				(_, _, newValue) -> onSelect(newValue));
	}

    private void onSelect(ToolWrapper tool) {
		txaDesc.setText(tool.getDescription());
		txaParams.setText(tool.getParameters());
    }


	private static record ToolWrapper(AiServiceTool tool) {
		public String getDescription() {
			return tool.toolSpecification().description();
		}
		public String getParameters() {
			// TODO: pretty-print the JSON tool params with Jackson
			return tool.toolSpecification().parameters().toString();
		}
		@Override public String toString() {
			return tool.name()+": "+extractSnippet(getDescription(), 80);
		}
		private String extractSnippet(String in, int len) {
			if (in==null) return "";
			String end = "";
			if (in.length() > len) end="...";
			else len = in.length();
			return in.stripLeading().substring(0, len).replace("\n", "  ")+end;
		}
	}
}
