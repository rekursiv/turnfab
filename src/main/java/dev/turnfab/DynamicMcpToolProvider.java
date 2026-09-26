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
 * <p>Filters and the specification mapper set through the inherited setters remain in effect.
 */
public class DynamicMcpToolProvider extends McpToolProvider {

    public static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";

    private static final Logger log = Logger.getLogger(DynamicMcpToolProvider.class.getName());

    /**
     * Everything needed to connect to one MCP server, typically loaded from a config file.
     * A null {@code headers} or {@code protocolVersion} is normalized to empty/default.
     */
    public record ServerConfig(String key, String url, Map<String, String> headers, String protocolVersion) {
        public ServerConfig {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("server key is required");
            if (url == null || url.isBlank()) throw new IllegalArgumentException("server url is required");
            headers = (headers == null) ? Map.of() : Map.copyOf(headers);
            protocolVersion = (protocolVersion == null) ? DEFAULT_PROTOCOL_VERSION : protocolVersion;
        }

        /** Convenience for the common "Authorization: Bearer ..." header. */
        public static ServerConfig withBearerAuth(String key, String url, String apiKey) {
            return new ServerConfig(key, url, Map.of("Authorization", "Bearer " + apiKey), null);
        }
    }

    // ConcurrentSkipListMap keeps iteration stable (sorted by key) and safe while
    // provideTools runs on a streaming thread and the UI thread adds/removes servers.
    private final ConcurrentSkipListMap<String, McpClient> mcpMap = new ConcurrentSkipListMap<>();
    private final Set<String> disabledKeys = ConcurrentHashMap.newKeySet();

    public DynamicMcpToolProvider() {
        // The parent's internal client list is intentionally left empty: mcpMap is the single
        // source of truth, passed explicitly on every provideTools call.
        super(List.of(), false, (mcpClient, tool) -> true, Function.identity(), null, null, null);
    }

    /**
     * Builds and verifies one server connection, then registers it. This is the function a
     * config-file loader (or a "connect to this server now" tool) should call.
     *
     * @return the newly registered client
     * @throws RuntimeException if the server cannot be reached or lists no tools
     */
    public McpClient addServer(ServerConfig config) {
        McpTransport transport = StreamableHttpMcpTransport.builder()
                .url(config.url())
                .customHeaders(config.headers())
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

        addMcpClient(client);
        log.info("MCP server '" + config.key() + "' ready with " + tools.size() + " tools.");
        return client;
    }

    /**
     * Registers a client, replacing (but not closing) any client with the same key.
     */
    @Override
    public void addMcpClient(McpClient client) {
        Objects.requireNonNull(client, "client");
        McpClient replaced = mcpMap.put(client.key(), client);
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
     * Removes a client by key without closing it.
     *
     * @return the removed client, or null if no client was registered under that key
     */
    public McpClient removeMcpClientByKey(String key) {
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

    /** Closes and forgets every registered client. */
    public void closeAll() {
        for (McpClient client : mcpMap.values()) {
            quietlyClose(client);
        }
        mcpMap.clear();
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
