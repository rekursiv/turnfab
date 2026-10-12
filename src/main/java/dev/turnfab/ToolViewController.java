package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.inject.Inject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;

import java.util.Map;
import java.util.logging.Logger;

@FXMLController
public class ToolViewController {

	@Inject private Logger log;
	@Inject private DynamicMcpToolProvider toolProvider;

	@FXML private ListView<ToolWrapper> lsvTools;  // TODO: replace with TableView<ToolWrapper> tbvTools
	// TODO: TableColumn<ToolWrapper, String> tbcToolName
	// TODO: TableColumn<ToolWrapper, Integer> tbcToolNumParams
	// TODO: TableColumn<ToolWrapper, String> tbcToolDesc

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
			try {
				Map<String, Object> map = JsonSchemaElementJsonUtils.toMap(
						tool.toolSpecification().parameters());
				return new ObjectMapper().writerWithDefaultPrettyPrinter()
						.writeValueAsString(map);
			} catch (JsonProcessingException e) {
				return String.valueOf(tool.toolSpecification().parameters());
			}
		}
		public int numParameters() {
			return tool.toolSpecification().parameters().properties().entrySet().toArray().length;
		}
		@Override public String toString() {
			return tool.name()+": ("+numParameters()+")   "+extractSnippet(getDescription(), 80);
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
