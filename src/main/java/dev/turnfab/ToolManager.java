package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ToolManager {

    @Inject private Logger log;
    @Inject private TurnfabConfig mainCfg;
    @Inject private DynamicMcpToolProvider toolProvider;

    private final ConfigManager<McpServerConfig> mcpServerCfgMgr = ConfigManager.yaml(McpServerConfig.class, "../config/McpServers.yaml");
    private final ConfigManager<McpJetBrainsConfig> jbProjectCfgMgr = ConfigManager.yaml(McpJetBrainsConfig.class, "../config/McpJetBrainsConfig.yaml");
    private final ConfigManager<ToolContextConfig> toolCfgMgr = ConfigManager.yaml(ToolContextConfig.class, "../config/ToolContext.yaml");

    public void init() {
        Set<String> enabled = mainCfg.mcpServers;
        ToolContextConfig tcc = toolCfgMgr.load();
        for (String tc : mainCfg.toolContext) {
            List<ToolContextConfig.Context> ctx = tcc.context.get(tc);
            if (ctx==null) {
                System.out.println("No tool context found for " + tc);
            } else {
                for (ToolContextConfig.Context c : ctx) {
                    if (c.mcpName!=null) enabled.add(c.mcpName);
                }
            }
        }

        McpServerConfig rawCfg = mcpServerCfgMgr.load();
        McpJetBrainsConfig jbProjects = jbProjectCfgMgr.load();

        List<McpServerConfig.Server> mergedCfg = new ArrayList<>();
        mergedCfg.addAll(rawCfg.servers);

        for (McpServerConfig.Server server : rawCfg.servers) {
            if (server.name.equals("jetbrains")) {
                for (McpJetBrainsConfig.Project project : jbProjects.projects) {
                    McpServerConfig.Server ns = new McpServerConfig.Server();
                    ns.name = project.name;
                    ns.url = "http://127.0.0.1:"+project.port+"/stream";
                    ns.headers.put("IJ_MCP_SERVER_PROJECT_PATH", project.projectPath);
                    ns.protocolVersion = server.protocolVersion;
                    ns.excludeTools.addAll(server.excludeTools);
                    ns.includeTools.addAll(server.includeTools);
                    if (project.readOnly) ns.excludeTools.addAll(server.excludeWriteTools);
                    ns.debugTransport = server.debugTransport;
                    mergedCfg.add(ns);
                }
            }
        }

        for (McpServerConfig.Server server : mergedCfg) {
            if (enabled.contains(server.name)) addServer(server);
        }

        toolProvider.setToolSpecificationMapper(new TurnfabToolSpecMapper());

    }

    public void addServer(McpServerConfig.Server server) {
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
