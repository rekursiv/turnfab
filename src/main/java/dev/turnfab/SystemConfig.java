package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class SystemConfig extends ConfigBase {
	public boolean logToConsole=false;
	public boolean logToFile=false;

	public boolean enableThinking = true;
	public boolean logRequests = false;
	public boolean logResponses = false;

    public String systemPromptFileName = "coder.md";

	public List<String> mcpServers = new ArrayList<>();
	public List<String> toolContext = new ArrayList<>();



}
