package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnfabConfig extends ConfigBase {

	// logging
	public boolean logToConsole=true;
	public boolean logToFile=false;


	public String model_name = "Qwen/Qwen3.8-27B";
	public String model_base_url = "http://venus.local:8000/v1";
	public String model_key = "secret";
	public int model_length = 262144;

	public String github_mcp_name = "github";
	public String github_mcp_url = "https://api.githubcopilot.com/mcp";
	public String github_mcp_key = "secret";

	public String tavily_mcp_name = "tavily";
	public String tavily_mcp_url = "https://mcp.tavily.com/mcp";
	public String tavily_mcp_key = "secret";


}
