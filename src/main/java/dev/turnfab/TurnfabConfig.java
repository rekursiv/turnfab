package dev.turnfab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnfabConfig extends ConfigBase {

	// logging
	public boolean logToConsole=true;
	public boolean logToFile=false;

	public boolean github_mcp_enabled=false;
	public String github_mcp_name = "github";
	public String github_mcp_url = "https://api.githubcopilot.com/mcp";
	public String github_mcp_key = "secret";

	public boolean tavily_mcp_enabled=false;
	public String tavily_mcp_name = "tavily";
	public String tavily_mcp_url = "https://mcp.tavily.com/mcp";
	public String tavily_mcp_key = "secret";

	public boolean jetbrains_mcp_enabled=true;
	public String jetbrains_mcp_name = "jetbrains";
	public String jetbrains_mcp_url = "http://127.0.0.1:64436/stream";

	public boolean debug_mcp_transport = false;
}
