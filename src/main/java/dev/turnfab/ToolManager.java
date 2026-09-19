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

    private static final boolean tavily_mcp_enabled = false;
    private static final boolean github_mcp_enabled = false;
    private static final boolean jetbrains_mcp_enabled = true;

    private static final String PROJECT_PATH = "C:/projects/intellij_workspace/turnfab";

    private static final boolean DEBUG_MCP_TRANSPORT = false;




    @Inject private Logger log;
    @Inject private TurnfabConfig cfg;

    private McpClient jbMcpClient = null;
    private McpClient tavilyMcpClient = null;
    private McpClient githubMcpClient = null;

    private McpToolProvider toolProvider = McpToolProvider.builder().mcpClients(List.of()).build();


    public McpToolProvider getProvider() {
        return toolProvider;
    }

    public void init() {
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
        if (jetbrains_mcp_enabled) {
            jbMcpClient = setupMcpClient(cfg.jetbrains_mcp_name, cfg.jetbrains_mcp_url,
                    Map.of("IJ_MCP_SERVER_PROJECT_PATH", PROJECT_PATH));
            toolProvider.addFilter((mc, tool) ->
                    !mc.key().equals(cfg.jetbrains_mcp_name) || !jbToolExcludeList().contains(tool.name()));
            toolProvider.addMcpClient(jbMcpClient);
            //		test(jbMcpClient);
        }

        toolProvider.setToolNameMapper((mc, tool) ->
                mc.key().equals("tavily") ? tool.name() : mc.key()+"_"+tool.name());

//        printEnabledTools();

    }

    public void printEnabledTools() {
        ToolProviderResult tpr = toolProvider.provideTools(null);
        for (AiServiceTool tool : tpr.aiServiceTools()) {
            System.out.println(tool.name()+": "+tool.toolSpecification().description());
        }
    }

    public McpClient getJbMcpClient() {
        return jbMcpClient;
    }

    //////

    private List<String> jbToolExcludeList() {
        return List.of("execute_tool", "execute_terminal_command");
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
