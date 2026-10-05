package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnfabConfig extends ConfigBase {
	public boolean logToConsole=true;
	public boolean logToFile=false;

	public boolean enableThinking = true;
    public String systemPromptFileName = "coder.md";

	public List<String> mcpServers = new ArrayList<>();
	public List<String> toolContext = new ArrayList<>();

	public boolean logRequests = false;
	public boolean logResponses = false;

}
