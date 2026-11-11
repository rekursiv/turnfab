package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
//@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class SetupConfig extends ConfigBase {

	public boolean enableThinking = true;
	public boolean logRequests = false;
	public boolean logResponses = false;

	public String modelConfig = "model_qfn.yaml";
    public String systemPrompt = "You are a friendly, conversational chatbot.";
	public boolean addDateToSystemPrompt = true;

	public List<String> mcpServers = new ArrayList<>();
	public List<String> toolContext = new ArrayList<>();

	public String userPrompt = null;


}
