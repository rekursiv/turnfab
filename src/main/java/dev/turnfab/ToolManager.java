package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;


public class ToolManager {

    private static final boolean mainprj_mcp_enabled = true;
    private static final String mainprj_mcp_name = "main_project";
    private static final String MAIN_PROJECT_PATH = "C:/projects/intellij_workspace/turnfab";

    private static final boolean lc4j_mcp_enabled = true;
    private static final String lc4j_mcp_name = "langchain4j_src";
    private static final String LC4J_PROJECT_PATH = "C:/projects/intellij_workspace/langchain4j";

    private static final boolean msvsrc_mcp_enabled = false;
    private static final String msvsrc_mcp_name = "markstream_vue";
    private static final String MSV_SRC_PATH = "C:/projects/intellij_workspace/markstream-vue";

    private static final boolean tavily_mcp_enabled = false;
    private static final boolean github_mcp_enabled = false;

    private static final boolean DEBUG_MCP_TRANSPORT = false;


    @Inject private Logger log;
    @Inject private TurnfabConfig cfg;

    private McpClient mainprjMcpClient = null;
    private McpClient lc4jClient = null;
    private McpClient msvsrcMcpClient = null;


    private McpClient tavilyMcpClient = null;
    private McpClient githubMcpClient = null;

    private McpToolProvider toolProvider = McpToolProvider.builder().mcpClients(List.of()).build();


    public McpToolProvider getProvider() {
        return toolProvider;
    }

    public void init() {

        if (mainprj_mcp_enabled) {
            mainprjMcpClient = setupMcpClient(mainprj_mcp_name, "http://127.0.0.1:64436/stream",
                    Map.of("IJ_MCP_SERVER_PROJECT_PATH", MAIN_PROJECT_PATH));
            toolProvider.addFilter((mc, tool) ->
                    !mc.key().equals(mainprj_mcp_name) || !jbToolExcludeList_RW().contains(tool.name()));
            toolProvider.addMcpClient(mainprjMcpClient);
        }
        if (lc4j_mcp_enabled) {
            lc4jClient = setupMcpClient(lc4j_mcp_name, "http://127.0.0.1:64436/stream",
                    Map.of("IJ_MCP_SERVER_PROJECT_PATH", LC4J_PROJECT_PATH));
            toolProvider.addFilter((mc, tool) ->
                    !mc.key().equals(lc4j_mcp_name) || !jbToolExcludeList_RO().contains(tool.name()));
            toolProvider.addMcpClient(lc4jClient);
        }
        if (msvsrc_mcp_enabled) {
            msvsrcMcpClient = setupMcpClient(msvsrc_mcp_name, "http://127.0.0.1:64542/stream",
                    Map.of("IJ_MCP_SERVER_PROJECT_PATH", MSV_SRC_PATH));
            toolProvider.addFilter((mc, tool) ->
                    !mc.key().equals(msvsrc_mcp_name) || !jbToolExcludeList_RO().contains(tool.name()));
            toolProvider.addMcpClient(msvsrcMcpClient);
        }


        if (tavily_mcp_enabled) {
            tavilyMcpClient = setupMcpClient(cfg.tavily_mcp_name, cfg.tavily_mcp_url,
                    Map.of("Authorization", "Bearer "+cfg.tavily_mcp_key));
            toolProvider.addMcpClient(tavilyMcpClient);
        }
        if (github_mcp_enabled) {
            githubMcpClient = setupMcpClient(cfg.github_mcp_name, cfg.github_mcp_url,
                    Map.of("Authorization", "Bearer "+cfg.github_mcp_key));
            toolProvider.addFilter((mc, tool) ->
                    !mc.key().equals(cfg.github_mcp_name) || githubToolIncludeList().contains(tool.name()));
            toolProvider.addMcpClient(githubMcpClient);
        }

        toolProvider.setToolNameMapper((mc, tool) ->
                mc.key().equals("tavily") ? tool.name() : mc.key()+"-"+tool.name());


    }

    public void printEnabledTools() {
        ToolProviderResult tpr = toolProvider.provideTools(null);
        for (AiServiceTool tool : tpr.aiServiceTools()) {
//            System.out.println(tool.name()+":  "+tool.toolSpecification().parameters());
            System.out.println(tool.name()+":\t\t"+extractSnippet(tool.toolSpecification().description(), 120));
        }
    }

    public McpClient getMainprjMcpClient() { return mainprjMcpClient; }
    public McpClient getLc4jMcpClient() { return lc4jClient; }
    public McpClient getMsvsrcMcpClient() { return  msvsrcMcpClient; }

    //////

    private String extractSnippet(String in, int len) {
        if (in==null) return "";
        String end = "";
        if (in.length() > len) end="...";
        else len = in.length();
        return in.stripLeading().substring(0, len).replace("\n", "  ")+end;
    }

    private List<String> jbToolExcludeList_RW() {
        return List.of("execute_tool", "execute_terminal_command", "get_all_open_file_paths", "open_file_in_editor");
    }

    private List<String> jbToolExcludeList_RO() {
        return List.of("execute_tool", "execute_terminal_command", "build_project", "create_new_file",
                "get_all_open_file_paths", "open_file_in_editor", "apply_patch", "rename_refactoring");
    }

    private List<String> githubToolIncludeList() {
        return List.of("get_commit", "get_file_contents", "get_label",
                "get_latest_release", "get_release_by_tag", "get_tag", "issue_read",
                "list_branches", "list_commits", "list_issue_fields", "list_issue_types",
                "list_issues", "list_pull_requests", "list_releases", "list_repository_collaborators", "list_tags",
                "pull_request_read", "search_code", "search_commits", "search_issues", "search_pull_requests",
                "search_repositories", "search_users");
    }


    private McpClient setupMcpClient(String name, String url, Map<String, String> headers) {
        log.info("Connecting to mcp transport at " + url);
        McpTransport mcpTransport = StreamableHttpMcpTransport.builder()
                .url(url)
                .customHeaders(headers)
                .logResponses(DEBUG_MCP_TRANSPORT)
                .logRequests(DEBUG_MCP_TRANSPORT)
                .build();
        return DefaultMcpClient.builder()
                .key(name)
                .transport(mcpTransport)
                .protocolVersion("2025-11-25")
                .build();
    }

    private void test(McpClient mcpClient) {

        if (mcpClient.instructions() == null) {
            System.out.println("No instructions for MCP client " + mcpClient.key());
        } else {
            System.out.println("MCP client " + mcpClient.key() + " instructions:\n\n" + mcpClient.instructions());
        }

        for (ToolSpecification ts : mcpClient.listTools()) {
            System.out.println(ts.toString());
        }
    }
}
