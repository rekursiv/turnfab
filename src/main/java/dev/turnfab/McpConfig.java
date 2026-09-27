package dev.turnfab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Persisted configuration of the MCP servers turnfab connects to, stored as YAML
 * (../config/McpConfig.yaml) via {@code ConfigManager.yaml(...)}. The entry list is the
 * source of truth once the file exists; on a first run {@code ToolManager} seeds it from
 * its built-in constants plus the tavily/github entries still found in TurnfabConfig, so
 * nothing breaks when the secrets live there today.
 *
 * <p>Field layout mirrors {@code DynamicMcpToolProvider.ServerConfig} 1:1 on purpose —
 * a loader can map one to the other without translation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpConfig extends ConfigBase {

	public List<Server> servers = new ArrayList<>();

	/** One MCP server; maps straight onto ServerConfig. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Server {
		public String name = "";
		public String url = "";
		public Map<String, String> headers = new HashMap<>();
		public String protocolVersion = DynamicMcpToolProvider.DEFAULT_PROTOCOL_VERSION;
		public List<String> excludeTools = new ArrayList<>();
		public List<String> includeTools = new ArrayList<>();
		public boolean enabled = true;
		public boolean debugTransport = false;
	}
}
