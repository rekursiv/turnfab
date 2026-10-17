package dev.turnfab;

import com.cathive.fx.guice.FXMLController;
import com.google.inject.Inject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.logging.Logger;

@FXMLController
public class ToolViewController {

	@Inject private Logger log;
	@Inject private DynamicMcpToolProvider toolProvider;

	@FXML private TableView<ToolWrapper> tbvTools;
	@FXML private TableColumn<ToolWrapper, String> tbcToolName;
	@FXML private TableColumn<ToolWrapper, Integer> tbcToolNumParams;
	@FXML private TableColumn<ToolWrapper, String> tbcToolDesc;

	@FXML private TextArea txaDesc;
	@FXML private TextArea txaParams;

	@FXML
	private void initialize() {
		tbvTools.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
		// Columns are declared in FXML; the values come from ToolWrapper accessors here.
		tbcToolName.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getName()));
		tbcToolNumParams.setCellValueFactory(f -> new SimpleIntegerProperty(f.getValue().getNumParameters()).asObject());
		tbcToolDesc.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getDescForTable()));

		ToolProviderResult tpr = toolProvider.provideTools(null);
		tbvTools.setItems(FXCollections.observableArrayList(
				tpr.aiServiceTools().stream().map(ToolWrapper::new).toList()));
		tbvTools.getSelectionModel().selectedItemProperty().addListener(
				(_, _, newValue) -> onSelect(newValue));
	}

    private void onSelect(ToolWrapper tool) {
		if (tool == null) {
			txaDesc.clear();
			txaParams.clear();
			return;
		}
		txaDesc.setText(tool.getDescription());
		txaParams.setText(tool.getParameters());
    }


	private record ToolWrapper(AiServiceTool tool) {
		public String getName() {
			return tool.name();
		}
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
		public int getNumParameters() {
			return tool.toolSpecification().parameters().properties().size();
		}
		/**
		 * Full description, collapsed to one line. No length cap: the cell truncates
		 * itself with an ellipsis, so widening the column shows more text.
		 */
		public String getDescForTable() {
			return FLATTEN.matcher(getDescription()==null?"":getDescription()).replaceAll(" ").trim();
		}
		@Override public String toString() {
			return getName()+": ("+getNumParameters()+")   "+getDescForTable();
		}

		private static final Pattern FLATTEN = Pattern.compile("\\s*\\R\\s*");
	}
}
