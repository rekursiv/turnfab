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

    // Seed-only constants: copied into McpConfig.yaml on first run, after which the YAML
    // is the source of truth and these stop mattering. (Edit ../config/McpConfig.yaml instead.)
    private static final String MAINPRJ_MCP_NAME = "main_project";
    private static final String MAIN_PROJECT_PATH = "C:/projects/intellij_workspace/turnfab";
//    private static final String MAIN_PROJECT_PATH = "C:/projects/intellij_workspace/protoplant/protoplant_java";
    private static final String LC4J_MCP_NAME = "langchain4j_src";
    private static final String LC4J_PROJECT_PATH = "C:/projects/intellij_workspace/langchain4j";

    private static final String IJ_MCP_URL = "http://127.0.0.1:64506/stream";
    private static final String MSV_MCP_URL = "http://127.0.0.1:64542/stream";
    private static final String MSV_MCP_NAME = "markstream_vue";
    private static final String MSV_SRC_PATH = "C:/projects/intellij_workspace/markstream-vue";

    private static final Set<String> JB_EXCLUDE_RW = Set.of(
            "execute_tool", "execute_terminal_command", "get_all_open_file_paths", "open_file_in_editor");
    private static final Set<String> JB_EXCLUDE_RO = Set.of(
            "execute_tool", "execute_terminal_command", "build_project", "create_new_file",
            "get_all_open_file_paths", "open_file_in_editor", "apply_patch", "rename_refactoring");

    // GitHub is read-only: offer only this allow-list, everything else stays hidden.
    private static final Set<String> GITHUB_INCLUDE = Set.of(
            "get_commit", "get_file_contents", "get_label",
            "get_latest_release", "get_release_by_tag", "get_tag", "issue_read",
            "list_branches", "list_commits", "list_issue_fields", "list_issue_types",
            "list_issues", "list_pull_requests", "list_releases", "list_repository_collaborators", "list_tags",
            "pull_request_read", "search_code", "search_commits", "search_issues", "search_pull_requests",
            "search_repositories", "search_users");

    @Inject private Logger log;
    @Inject private TurnfabConfig cfg;
    @Inject private DynamicMcpToolProvider toolProvider;

    private final ConfigManager<McpConfig> mcpCfgMgr = ConfigManager.yaml(McpConfig.class, "../config/McpConfig.yaml");

    public DynamicMcpToolProvider getProvider() {
        return toolProvider;
    }

    /**
     * Registers every enabled server from McpConfig.yaml. Called by the "Init MCP" button:
     * the IDE-embedded servers are only reachable once the owning IntelliJ instance is up,
     * so connecting is an explicit user action, not app startup.
     */
    public void init() {
        McpConfig config = mcpCfgMgr.load();

        if (config.servers.isEmpty()) {
            seedDefaults(config);
            if (!config.cfgUseDefaults) {
                try {
                    mcpCfgMgr.save(config);
                    log.info("Wrote seed MCP config to " + mcpCfgMgr.buildPath(mcpCfgMgr.defaultFileName));
                } catch (Exception e) {
                    log.log(Level.WARNING, "Could not write seed MCP config", e);
                }
            }
        }

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

    /** First-run contents of McpConfig.yaml, using TurnfabConfig for the two API-key servers. */
    private void seedDefaults(McpConfig config) {
        config.servers.add(seedServer(MAINPRJ_MCP_NAME, IJ_MCP_URL,
                Map.of("IJ_MCP_SERVER_PROJECT_PATH", MAIN_PROJECT_PATH), JB_EXCLUDE_RW, Set.of(), true));
        config.servers.add(seedServer(LC4J_MCP_NAME, IJ_MCP_URL,
                Map.of("IJ_MCP_SERVER_PROJECT_PATH", LC4J_PROJECT_PATH), JB_EXCLUDE_RO, Set.of(), true));
        config.servers.add(seedServer(MSV_MCP_NAME, MSV_MCP_URL,
                Map.of("IJ_MCP_SERVER_PROJECT_PATH", MSV_SRC_PATH), JB_EXCLUDE_RO, Set.of(), false));
        config.servers.add(seedServer(cfg.tavily_mcp_name, cfg.tavily_mcp_url,
                bearer(cfg.tavily_mcp_key), Set.of(), Set.of(), true));
        config.servers.add(seedServer(cfg.github_mcp_name, cfg.github_mcp_url,
                bearer(cfg.github_mcp_key), Set.of(), GITHUB_INCLUDE, true));
    }

    private static McpConfig.Server seedServer(String name, String url, Map<String, String> headers,
                                               Set<String> excludeTools, Set<String> includeTools, boolean enabled) {
        McpConfig.Server server = new McpConfig.Server();
        server.name = name;
        server.url = url;
        server.headers.putAll(headers);
        server.excludeTools.addAll(excludeTools);
        server.includeTools.addAll(includeTools);
        server.enabled = enabled;
        return server;
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

    public McpClient getMainprjMcpClient() { return toolProvider.getMcpClientByKey(MAINPRJ_MCP_NAME); }
    public McpClient getLc4jMcpClient() { return toolProvider.getMcpClientByKey(LC4J_MCP_NAME); }
    public McpClient getMsvMcpClient() { return  toolProvider.getMcpClientByKey(MSV_MCP_NAME); }

    public String getMcpInst() {
        return getMcpInst(toolProvider.getMcpClientByKey(cfg.github_mcp_name));
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
