package dev.turnfab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServers extends ConfigBase {

	public List<Server> servers = new ArrayList<>();

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Server {
		public String name = "";
		public String url = "";
		public Map<String, String> headers = new HashMap<>();
		public String protocolVersion = DynamicMcpToolProvider.DEFAULT_PROTOCOL_VERSION;
		public List<String> excludeTools = new ArrayList<>();
		public List<String> excludeWriteTools = new ArrayList<>();
		public List<String> includeTools = new ArrayList<>();
		public boolean debugTransport = false;
	}
}
