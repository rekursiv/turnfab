package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ToolManager {

    @Inject private Logger log;
    @Inject private TurnfabConfig mainCfg;
    @Inject private DynamicMcpToolProvider toolProvider;
    @Inject private ToolContextManager ctxMgr;

    private final ConfigManager<McpServerConfig> mcpServerCfgMgr = ConfigManager.yaml(McpServerConfig.class, "../config/McpServers.yaml");
    private final ConfigManager<McpJetBrainsConfig> jbProjectCfgMgr = ConfigManager.yaml(McpJetBrainsConfig.class, "../config/McpJetBrainsConfig.yaml");
    private final ConfigManager<ToolContextConfig> toolCfgMgr = ConfigManager.yaml(ToolContextConfig.class, "../config/ToolContext.yaml");

    public void init() {
        List<String> enabled = mainCfg.mcpServers;
        if (enabled==null) enabled = new ArrayList<>();
        ToolContextConfig tcc = toolCfgMgr.load();

        if (mainCfg.toolContext!=null) {
            for (String tc : mainCfg.toolContext) {
                List<ToolContextConfig.Context> ctx = tcc.context.get(tc);
                if (ctx == null) {
                    System.out.println("No tool context found for " + tc);
                } else {
                    for (ToolContextConfig.Context c : ctx) {
                        if (c.mcpName != null) enabled.add(c.mcpName);
                    }
                }
            }
        }

        McpServerConfig rawCfg = mcpServerCfgMgr.load();
        McpJetBrainsConfig jbProjects = jbProjectCfgMgr.load();

        Map<String, McpServerConfig.Server> mergedCfg = new LinkedHashMap<>(rawCfg.servers);

        // Expand the "jetbrains" template into one server per configured project.
        McpServerConfig.Server jb = mergedCfg.remove("jetbrains");
        if (jb != null) {
            for (McpJetBrainsConfig.Project project : jbProjects.projects) {
                McpServerConfig.Server ns = new McpServerConfig.Server();
                ns.url = "http://127.0.0.1:"+project.port+"/stream";
                ns.headers.put("IJ_MCP_SERVER_PROJECT_PATH", project.projectPath);
                ns.protocolVersion = jb.protocolVersion;
                ns.excludeTools.addAll(jb.excludeTools);
                ns.includeTools.addAll(jb.includeTools);
                if (project.readOnly) ns.excludeTools.addAll(jb.excludeWriteTools);
                ns.debugTransport = jb.debugTransport;
                mergedCfg.put(project.name, ns);
            }
        }

        // For each enabled name, look it up and register it.
        for (String name : enabled) {
            McpServerConfig.Server s = mergedCfg.get(name);
            if (s != null) addServer(name, s);
        }

        ctxMgr.resetPrompt();
        if (mainCfg.toolContext!=null) {
            for (String tc : mainCfg.toolContext) {
                ctxMgr.buildPrompt(tcc.context.get(tc));
            }
        }

        toolProvider.setToolSpecificationMapper(new TurnfabToolSpecMapper());

    }

    public void addServer(String name, McpServerConfig.Server server) {
        try {
            toolProvider.addServer(new DynamicMcpToolProvider.ServerConfig(
                    name, server.url, server.headers, server.protocolVersion,
                    toSet(server.excludeTools), toSet(server.includeTools),
                    server.debugTransport));
        } catch (RuntimeException e) {
            // one unreachable server should not block the others
            log.log(Level.WARNING, "MCP server '" + name + "' not connected: " + e.getMessage(), e);
        }
    }

    public String getPrompt() {
        return ctxMgr.getPrompt();
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
