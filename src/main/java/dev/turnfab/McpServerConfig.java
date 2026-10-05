package dev.turnfab;

import java.util.*;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig extends ConfigBase {

	public Map<String, Server> servers = new LinkedHashMap<>();

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Server {
		public String url = "";
		public Map<String, String> headers = new LinkedHashMap<>();
		public String protocolVersion = DynamicMcpToolProvider.DEFAULT_PROTOCOL_VERSION;
		public List<String> excludeTools = new ArrayList<>();
		public List<String> excludeWriteTools = new ArrayList<>();
		public List<String> includeTools = new ArrayList<>();
		public boolean debugTransport = false;
	}
}
