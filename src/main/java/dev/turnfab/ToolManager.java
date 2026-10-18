package dev.turnfab;

import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ToolManager {

    @Inject private Logger log;
    @Inject private EventBus eb;
    @Inject private SystemConfig mainCfg;
    @Inject private DynamicMcpToolProvider toolProvider;
    @Inject private ToolContextManager ctxMgr;

    private final ConfigManager<McpServerConfig> mcpServerCfgMgr =
            ConfigManager.yaml(McpServerConfig.class, "../turnfab_config/mcp_servers.yaml");
    private final ConfigManager<McpJetBrainsConfig> jbProjectCfgMgr =
            ConfigManager.yaml(McpJetBrainsConfig.class, "../turnfab_config/jet_brains.yaml");
    private final ConfigManager<ToolContextConfig> toolCfgMgr =
            ConfigManager.yaml(ToolContextConfig.class, "../turnfab_config/tool_context.yaml");

    private McpServerConfig mcpServerCfg;
    private McpServerConfig.Server jbMcpMetaDef;
    private ToolContextConfig toolCfg;
    /**
     * The server names the last {@link #loadTools()} tried to connect. Kept because loading a session is
     * a replace, not an add: what the session did not ask for has to be closed, not just left running.
     */
    private List<String> enabledNames;

    public void init() {
        mcpServerCfg = mcpServerCfgMgr.load();

        // store Jet Brains meta-definition for merging in loadTools()
        // and take out of main list of fully defined MCP servers
        jbMcpMetaDef = mcpServerCfg.servers.remove("jetbrains");

       toolCfg = toolCfgMgr.load();
    }

    public List<String> getMcpServerList() {
        return mcpServerCfg.servers.keySet().stream().toList();
    }

    public List<String> getToolContextList() {
        return toolCfg.context.keySet().stream().toList();
    }

    public String getToolContextPreview(String name) {
        if (toolCfg==null) return "toolCfg is null.";
        List<ToolContextConfig.Context> context = toolCfg.context.get(name);
        if (context == null) return "name not found";
        return ctxMgr.getPreview(context);
    }

    public void loadTools() throws Exception {
        if (mcpServerCfg==null || jbMcpMetaDef==null || toolCfg==null) {
            log.warning("Tool configs are not loaded, so the tool setup stays as it is.");
            return;
        }

        List<String> enabled = new ArrayList<>(mainCfg.mcpServers);

        if (mainCfg.toolContext!=null) {
            for (String tc : mainCfg.toolContext) {
                List<ToolContextConfig.Context> ctx = toolCfg.context.get(tc);
                if (ctx == null) {
                    log.warning("No tool context found for " + tc);
                } else {
                    for (ToolContextConfig.Context c : ctx) {
                        if (c.mcpName != null) enabled.add(c.mcpName);
                    }
                }
            }
        }

        enabledNames = List.copyOf(enabled);
        McpJetBrainsConfig jbProjects = jbProjectCfgMgr.load();
        Map<String, McpServerConfig.Server> mergedCfg = new LinkedHashMap<>(mcpServerCfg.servers);

        // Expand the "jetbrains" template into one server per configured project.
        if (jbMcpMetaDef != null) {
            for (McpJetBrainsConfig.Project project : jbProjects.projects) {
                McpServerConfig.Server ns = new McpServerConfig.Server();
                ns.url = "http://127.0.0.1:"+project.port+"/stream";
                ns.headers.put("IJ_MCP_SERVER_PROJECT_PATH", project.projectPath);
                ns.protocolVersion = jbMcpMetaDef.protocolVersion;
                ns.excludeTools.addAll(jbMcpMetaDef.excludeTools);
                ns.includeTools.addAll(jbMcpMetaDef.includeTools);
                if (project.readOnly) ns.excludeTools.addAll(jbMcpMetaDef.excludeWriteTools);
                ns.debugTransport = jbMcpMetaDef.debugTransport;
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
                ctxMgr.buildPrompt(toolCfg.context.get(tc));
            }
        }

        toolProvider.setToolSpecificationMapper(new TurnfabToolSpecMapper());
    }


    public void addServer(String name, McpServerConfig.Server server) throws Exception {
        toolProvider.addServer(new DynamicMcpToolProvider.ServerConfig(
                name, server.url, server.headers, server.protocolVersion,
                toSet(server.excludeTools), toSet(server.includeTools),
                server.debugTransport));
    }

    public String getPrompt() {
        return ctxMgr.getPrompt();
    }

    public String getPromptSummary() {
        return ctxMgr.getPromptSummary();
    }




    /** Hand-edited YAML can carry an explicit null where a tool list was expected. */
    private static Set<String> toSet(java.util.List<String> list) {
        return (list == null) ? Set.of() : Set.copyOf(list);
    }

    public String getEnabledTools() {
        StringBuilder sb = new StringBuilder();
        ToolProviderResult tpr = toolProvider.provideTools(null);
        for (AiServiceTool tool : tpr.aiServiceTools()) {
            sb.append(tool.name());
            sb.append(" ");
        }
        return sb.toString();
    }

    /**
     * The tool setup as it stands: what the two selection lists say, plus which keys are actually
     * connected right now. The two can disagree, and both halves matter -- the lists are what a session
     * file has to restore, the connected keys are what the model could actually call.
     */
    public SessionFile.ToolSetup currentSetup() {
        return new SessionFile.ToolSetup(
                (mainCfg.mcpServers == null) ? List.of() : List.copyOf(mainCfg.mcpServers),
                (mainCfg.toolContext == null) ? List.of() : List.copyOf(mainCfg.toolContext),
                toolProvider.getMcpClientKeys());
    }

    /**
     * Puts the tool setup back the way a session file had it: selection first, then the connect that
     * selection drives, then a look at what the file expected to be talking to.
     *
     * @throws Exception if any server refuses to come up, exactly as "Load Tools" would
     */
    public List<String> applySetup(SessionFile.ToolSetup setup) throws Exception {
        mainCfg.mcpServers = new ArrayList<>(setup.mcpServers());
        mainCfg.toolContext = new ArrayList<>(setup.toolContext());
        enabledNames = null;
        loadTools();

        // If loadTools bailed out, enabledNames is still null and nothing gets closed: it is better to
        // keep the tools that are up than to tear them all down over a config that didn't load.
        if (enabledNames != null) {
            for (String key : toolProvider.getMcpClientKeys()) {
                if (!enabledNames.contains(key)) {
                    log.info("Closing MCP server '" + key + "'; not part of this session's setup.");
                    toolProvider.closeMcpClient(key);
                }
            }
        }

        List<String> live = toolProvider.getMcpClientKeys();
        for (String expected : setup.connected()) {
            if (!live.contains(expected)) {
                log.warning("This session used MCP server '" + expected + "'; it is not available now.");
            }
        }
        return live;
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

}
