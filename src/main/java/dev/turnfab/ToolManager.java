package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the MCP servers into the shared {@link DynamicMcpToolProvider}. The server list comes
 * from {@code ../config/McpConfig.yaml} (see {@link McpConfig}); what is left here are the
 * first-run seed defaults (used only until the YAML file exists) and the lookups/debug helpers
 * that will move to the ConfigController/GUI as that is built.
 */
public class ToolManager {

    @Inject private Logger log;
    @Inject private TurnfabConfig cfg;
    @Inject private DynamicMcpToolProvider toolProvider;

    private final ConfigManager<McpConfig> mcpCfgMgr = ConfigManager.yaml(McpConfig.class, "../config/McpConfig.yaml");

    /**
     * Registers every enabled server from McpConfig.yaml. Called by the "Init MCP" button:
     * the IDE-embedded servers are only reachable once the owning IntelliJ instance is up,
     * so connecting is an explicit user action, not app startup.
     */
    public void init() {
        McpConfig config = mcpCfgMgr.load();

        for (McpConfig.Server server : config.servers) {
            if (!server.enabled) {
                log.info("MCP server '" + server.name + "' disabled in config, skipping.");
                continue;
            }
            try {
                toolProvider.addServer(new DynamicMcpToolProvider.ServerConfig(
                        server.name, server.url, server.headers, server.protocolVersion,
                        toSet(server.excludeTools), toSet(server.includeTools),
                        server.debugTransport));
            } catch (RuntimeException e) {
                // one unreachable server should not block the others
                log.log(Level.WARNING, "MCP server '" + server.name + "' not connected: " + e.getMessage(), e);
            }
        }

        toolProvider.setToolSpecificationMapper(new TurnfabToolSpecMapper());
    }

    private static Map<String, String> bearer(String apiKey) {
        return Map.of("Authorization", "Bearer " + apiKey);
    }

    /** Hand-edited YAML can carry an explicit null where a tool list was expected. */
    private static Set<String> toSet(java.util.List<String> list) {
        return (list == null) ? Set.of() : Set.copyOf(list);
    }

    public void printEnabledTools() {
        ToolProviderResult tpr = toolProvider.provideTools(null);
        for (AiServiceTool tool : tpr.aiServiceTools()) {
            System.out.println(tool.name()+":\t\t"+extractSnippet(tool.toolSpecification().description(), 120));
        }
    }

    private String getMcpInst(McpClient mcpClient) {
        if (mcpClient == null) {
            return "MCP client not ready.";
        } else if (mcpClient.instructions() == null) {
            return "No instructions for MCP client " + mcpClient.key();
        } else {
            return "MCP client " + mcpClient.key() + " instructions:\n\n" + mcpClient.instructions();
        }
    }

    private String extractSnippet(String in, int len) {
        if (in==null) return "";
        String end = "";
        if (in.length() > len) end="...";
        else len = in.length();
        return in.stripLeading().substring(0, len).replace("\n", "  ")+end;
    }
}
