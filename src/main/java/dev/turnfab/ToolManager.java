package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads the turnfab MCP servers into a {@link DynamicMcpToolProvider}, which doubles as the
 * client registry: servers are registered via {@code addServer(...)} together with their tool
 * policy, and looked up by key through the provider. The former shadow {@code McpClient} fields
 * and per-server {@code addFilter} lambdas are gone: enable/disable is {@code setServerEnabled}
 * and tool visibility is data in the {@code _LIST}s below.
 *
 * <p>Eventually the {@code ServerConfig} objects will come from config files instead of these
 * constants; the shape is already identical, so only the loading changes.
 */
public class ToolManager {

    private static final String IJ_MCP_URL = "http://127.0.0.1:64506/stream";
    private static final String MSV_MCP_URL = "http://127.0.0.1:64542/stream";

    private static final String MAINPRJ_MCP_NAME = "main_project";
    private static final String MAIN_PROJECT_PATH = "C:/projects/intellij_workspace/turnfab";
//    private static final String MAIN_PROJECT_PATH = "C:/projects/intellij_workspace/protoplant/protoplant_java";
    private static final Set<String> JB_EXCLUDE_RW = Set.of(
            "execute_tool", "execute_terminal_command", "get_all_open_file_paths", "open_file_in_editor");

    private static final String LC4J_MCP_NAME = "langchain4j_src";
    private static final String LC4J_PROJECT_PATH = "C:/projects/intellij_workspace/langchain4j";
    private static final Set<String> JB_EXCLUDE_RO = Set.of(
            "execute_tool", "execute_terminal_command", "build_project", "create_new_file",
            "get_all_open_file_paths", "open_file_in_editor", "apply_patch", "rename_refactoring");

    private static final String MSV_MCP_NAME = "markstream_vue";
    private static final String MSV_SRC_PATH = "C:/projects/intellij_workspace/markstream-vue";

    // GitHub is read-only: offer only this allow-list, everything else stays hidden.
    private static final Set<String> GITHUB_INCLUDE = Set.of(
            "get_commit", "get_file_contents", "get_label",
            "get_latest_release", "get_release_by_tag", "get_tag", "issue_read",
            "list_branches", "list_commits", "list_issue_fields", "list_issue_types",
            "list_issues", "list_pull_requests", "list_releases", "list_repository_collaborators", "list_tags",
            "pull_request_read", "search_code", "search_commits", "search_issues", "search_pull_requests",
            "search_repositories", "search_users");

    // Formerly per-boolean constants deciding which servers connect; now just data here,
    // a config file would carry the same per entry, or use setServerEnabled at runtime.
    private static final boolean mainprj_mcp_enabled = true;
    private static final boolean lc4j_mcp_enabled = true;
    private static final boolean msvsrc_mcp_enabled = false;
    private static final boolean tavily_mcp_enabled = true;
    private static final boolean github_mcp_enabled = true;

    private static final boolean DEBUG_MCP_TRANSPORT = false;


    @Inject private Logger log;
    @Inject private TurnfabConfig cfg;
    @Inject private DynamicMcpToolProvider toolProvider;

    public DynamicMcpToolProvider getProvider() {
        return toolProvider;
    }

    public void init() {

        if (mainprj_mcp_enabled) {
            addServer(MAINPRJ_MCP_NAME, IJ_MCP_URL, Map.of("IJ_MCP_SERVER_PROJECT_PATH", MAIN_PROJECT_PATH),
                    JB_EXCLUDE_RW, Set.of());
        }
        if (lc4j_mcp_enabled) {
            addServer(LC4J_MCP_NAME, IJ_MCP_URL, Map.of("IJ_MCP_SERVER_PROJECT_PATH", LC4J_PROJECT_PATH),
                    JB_EXCLUDE_RO, Set.of());
        }
        if (msvsrc_mcp_enabled) {
            addServer(MSV_MCP_NAME, MSV_MCP_URL, Map.of("IJ_MCP_SERVER_PROJECT_PATH", MSV_SRC_PATH),
                    JB_EXCLUDE_RO, Set.of());
        }

        if (tavily_mcp_enabled) {
            addServer(cfg.tavily_mcp_name, cfg.tavily_mcp_url, bearer(cfg.tavily_mcp_key),
                    Set.of(), Set.of());
        }
        if (github_mcp_enabled) {
            addServer(cfg.github_mcp_name, cfg.github_mcp_url, bearer(cfg.github_mcp_key),
                    Set.of(), GITHUB_INCLUDE);
        }

        toolProvider.setToolSpecificationMapper(new TurnfabToolSpecMapper());
    }

    private McpClient addServer(String key, String url, Map<String, String> headers,
                                Set<String> excludeTools, Set<String> includeTools) {
        return toolProvider.addServer(new DynamicMcpToolProvider.ServerConfig(
                key, url, headers, null, excludeTools, includeTools, DEBUG_MCP_TRANSPORT));
    }

    private static Map<String, String> bearer(String apiKey) {
        return Map.of("Authorization", "Bearer " + apiKey);
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
