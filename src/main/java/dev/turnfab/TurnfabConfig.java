package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnfabConfig extends ConfigBase {
	public boolean logToConsole=true;
	public boolean logToFile=false;

	public boolean enableThinking = true;
    public String systemPromptFileName = "coder.md";
	public Set<String> mcpServers = new LinkedHashSet<>();
	public Set<String> toolContext = new LinkedHashSet<>();

	public boolean logRequests = false;
	public boolean logResponses = false;

}
