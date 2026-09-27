package dev.turnfab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.logging.Logger;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

/**
 * An {@link McpToolProvider} that doubles as the single registry of all live MCP clients,
 * keyed by {@link McpClient#key()}. It replaces the pattern of holding shadow references to
 * clients alongside the provider: clients go in through {@link #addServer(ServerConfig)} (or
 * {@link #addMcpClient(McpClient)}) and can be looked back up with {@link #getMcpClientByKey(String)}.
 *
 * <p>Each server registered through {@link #addServer(ServerConfig)} carries its own tool
 * policy (exclude/include lists, see {@link ServerConfig}); a single internal filter applies
 * them to every {@code provideTools} call, so tool visibility is pure configuration with no
 * per-server filter lambdas. Clients added raw through {@link #addMcpClient(McpClient)} have
 * no config and expose all of their tools.
 *
 * <p>The provider is {@link #isDynamic() dynamic}: tools are re-evaluated before every LLM call,
 * so servers added or removed between turns show up immediately on the next round, without
 * restarting the conversation.
 *
 * <p>Two caveats, both inherited from how langchain4j treats dynamic tool providers:
 * <ul>
 *   <li>Tools already exposed in a running AI-service invocation stay available until the end of
 *       that invocation. Disabling or removing a server mid-turn only takes full effect from the
 *       next turn.</li>
 *   <li>The protected {@code McpToolProvider} constructor used here forces
 *       {@code returnToolResultAttributes = false} and no always-visible tool names; if those
 *       builder options are ever needed, this class has to change.</li>
 * </ul>
 *
 * <p>Additional filters and the specification mapper set through the inherited setters remain
 * in effect. Note that the inherited {@code setFilter}/{@code resetFilters} replace the whole
 * filter chain, dropping this class's config-driven tool policy along with anything else added
 * via {@code addFilter} — prefer {@code addFilter}.
 */
public class DynamicMcpToolProvider extends McpToolProvider {

    public static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";

    private static final Logger log = Logger.getLogger(DynamicMcpToolProvider.class.getName());

    /**
     * Everything needed to connect to one MCP server and expose it to the model, typically
     * loaded from a config file. Null {@code headers}/{@code protocolVersion}/tool lists are
     * normalized to empty/default.
     *
     * <p>Tool policy: anything in {@code excludeTools} is hidden; if {@code includeTools} is
     * non-empty, only those tools are offered. An exclude hit always loses, so the lists may
     * overlap as "everything except ...".
     */
    public record ServerConfig(String key, String url, Map<String, String> headers, String protocolVersion,
                               Set<String> excludeTools, Set<String> includeTools, boolean debugTransport) {

        /** Servers without a tool policy, full protocol defaults. */
        public ServerConfig(String key, String url, Map<String, String> headers, String protocolVersion) {
            this(key, url, headers, protocolVersion, Set.of(), Set.of(), false);
        }

        public ServerConfig {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("server key is required");
            if (url == null || url.isBlank()) throw new IllegalArgumentException("server url is required");
            headers = (headers == null) ? Map.of() : Map.copyOf(headers);
            protocolVersion = (protocolVersion == null) ? DEFAULT_PROTOCOL_VERSION : protocolVersion;
            excludeTools = (excludeTools == null) ? Set.of() : Set.copyOf(excludeTools);
            includeTools = (includeTools == null) ? Set.of() : Set.copyOf(includeTools);
        }

        /** Convenience for the common "Authorization: Bearer ..." header. */
        public static ServerConfig withBearerAuth(String key, String url, String apiKey) {
            return new ServerConfig(key, url, Map.of("Authorization", "Bearer " + apiKey), null,
                    Set.of(), Set.of(), false);
        }
    }

    // ConcurrentSkipListMap keeps iteration stable (sorted by key) and safe while
    // provideTools runs on a streaming thread and the UI thread adds/removes servers.
    private final ConcurrentSkipListMap<String, McpClient> mcpMap = new ConcurrentSkipListMap<>();
    // Tool policy per key, present only for clients registered via addServer.
    private final ConcurrentSkipListMap<String, ServerConfig> configMap = new ConcurrentSkipListMap<>();
    private final Set<String> disabledKeys = ConcurrentHashMap.newKeySet();

    public DynamicMcpToolProvider() {
        // The parent's internal client list is intentionally left empty: mcpMap is the single
        // source of truth, passed explicitly on every provideTools call.
        super(List.of(), false, (mcpClient, tool) -> true, Function.identity(), null, null, null);
        // One config-driven policy filter for all servers: look up the registered config (if any)
        // and apply its exclude/include lists. Re-evaluated on every provideTools, so an
        // addServer-based config swap takes effect from the next round.
        addFilter((mcp, tool) -> {
            ServerConfig config = configMap.get(mcp.key());
            if (config == null) return true;
            if (!config.excludeTools().isEmpty() && config.excludeTools().contains(tool.name())) return false;
            if (!config.includeTools().isEmpty() && !config.includeTools().contains(tool.name())) return false;
            return true;
        });
    }

    /**
     * Builds and verifies one server connection, then registers it. This is the function a
     * config-file loader (or a "connect to this server now" tool) should call. Registers (or
     * replaces) under {@code config.key()} along with its tool policy, closing the connection
     * of any client previously registered under that key.
     *
     * @return the newly registered client
     * @throws RuntimeException if the server cannot be reached or lists no tools
     */
    public McpClient addServer(ServerConfig config) {
        McpTransport transport = StreamableHttpMcpTransport.builder()
                .url(config.url())
                .customHeaders(config.headers())
                .logRequests(config.debugTransport())
                .logResponses(config.debugTransport())
                .build();
        DefaultMcpClient client = DefaultMcpClient.builder()
                .key(config.key())
                .transport(transport)
                .protocolVersion(config.protocolVersion())
                .build();

        List<ToolSpecification> tools;
        try {
            tools = client.listTools();   // fail fast, and prime the client's tool cache
        } catch (RuntimeException e) {
            quietlyClose(client);
            throw new IllegalStateException("MCP server '" + config.key() + "' not usable: " + e.getMessage(), e);
        }

        McpClient previous = mcpMap.put(config.key(), client);
        configMap.put(config.key(), config);
        disabledKeys.remove(config.key());
        if (previous != null && previous != client) quietlyClose(previous);
        log.info("MCP server '" + config.key() + "' ready with " + tools.size() + " tools.");
        return client;
    }

    /**
     * Registers a client without a tool policy, replacing (but not closing) any client with the
     * same key. Any config previously registered for that key is dropped, so a raw-added client
     * exposes all of its tools; use {@link #addServer(ServerConfig)} to also carry a policy.
     */
    @Override
    public void addMcpClient(McpClient client) {
        Objects.requireNonNull(client, "client");
        McpClient replaced = mcpMap.put(client.key(), client);
        configMap.remove(client.key());
        disabledKeys.remove(client.key());
        if (replaced != null && replaced != client) {
            log.warning("Replaced MCP client '" + client.key()
                    + "'; close the previous instance explicitly if it is still open.");
        }
    }

    @Override
    public void removeMcpClient(McpClient client) {
        Objects.requireNonNull(client, "client");
        removeMcpClientByKey(client.key());
    }

    /**
     * Removes a client by key without closing it. Its tool policy goes with it.
     *
     * @return the removed client, or null if no client was registered under that key
     */
    public McpClient removeMcpClientByKey(String key) {
        configMap.remove(key);
        disabledKeys.remove(key);
        return mcpMap.remove(key);
    }

    /** Removes a client by key and closes it. */
    public void closeMcpClient(String key) {
        McpClient removed = removeMcpClientByKey(key);
        if (removed != null) quietlyClose(removed);
    }

    /**
     * Supplies tools only for registered <em>and enabled</em> clients. Disabled clients are skipped
     * before {@code listTools()}, so a disabled server is not pinged at all. Any filters or the
     * spec mapper set through the inherited setters are still applied on top by the parent.
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        List<McpClient> activeClients = new ArrayList<>();
        for (Map.Entry<String, McpClient> entry : mcpMap.entrySet()) {
            if (!disabledKeys.contains(entry.getKey())) {
                activeClients.add(entry.getValue());
            }
        }
        return super.provideTools(request, activeClients);
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    /**
     * Toggles a server without closing its connection: its tools disappear from the next round
     * and reappear when re-enabled, with no reconnect. Takes effect from the next turn (see
     * class javadoc about in-flight invocations).
     */
    public void setServerEnabled(String key, boolean enabled) {
        if (!mcpMap.containsKey(key)) {
            throw new IllegalArgumentException("No MCP client registered under key '" + key + "'");
        }
        if (enabled) disabledKeys.remove(key);
        else disabledKeys.add(key);
    }

    public boolean isServerEnabled(String key) {
        return mcpMap.containsKey(key) && !disabledKeys.contains(key);
    }

    /** The live registry lookup this whole class exists for. */
    public McpClient getMcpClientByKey(String key) {
        return mcpMap.get(key);
    }

    /** Registered server keys, sorted. */
    public List<String> getMcpClientKeys() {
        return List.copyOf(mcpMap.keySet());
    }

    /** Registered clients, sorted by key. */
    public List<McpClient> getMcpClients() {
        return List.copyOf(mcpMap.values());
    }

    public boolean hasServer(String key) {
        return mcpMap.containsKey(key);
    }

    /** The tool policy registered for a key, or null for raw-added clients. */
    public ServerConfig getServerConfig(String key) {
        return configMap.get(key);
    }

    /** Closes and forgets every registered client. */
    public void closeAll() {
        for (McpClient client : mcpMap.values()) {
            quietlyClose(client);
        }
        mcpMap.clear();
        configMap.clear();
        disabledKeys.clear();
    }

    private static void quietlyClose(McpClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warning("Failed to close MCP client '" + client.key() + "': " + e);
        }
    }
}
