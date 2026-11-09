package dev.turnfab;

import dev.langchain4j.mcp.client.McpToolResultConverter;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Logger;

/**
 * Turns an MCP {@code content[]} array into text without giving up when an item is not
 * {@code text}.
 *
 * <p>The shipped {@code DefaultMcpToolResultConverter} accepts only {@code type: "text"} and
 * throws {@code RuntimeException("Unsupported content type: \"...\"} for anything else, so a
 * single non-text block loses the whole result. This matters for the official GitHub MCP
 * server: reading a file answers with
 * <pre>
 *   [ { "type": "text",     "text": "<the message>" },
 *     { "type": "resource", "resource": { "uri": ..., "mimeType": ..., "text": ... } } ]
 * </pre>
 * and the <em>file contents live in the resource block</em>. Dropping non-text items would
 * therefore look like a success that returns only boilerplate, so the resource is rendered
 * rather than discarded.
 *
 * <p>Responses carrying {@code structuredContent} bypass this class; the client handles those
 * on its own path.
 *
 * <p>Binary blobs are summarized, not inlined: base64 of a file the model cannot decode costs
 * tokens and conveys nothing.
 */
public class LenientMcpToolResultConverter implements McpToolResultConverter {

    private static final Logger log = Logger.getLogger(LenientMcpToolResultConverter.class.getName());

    @Override
    public ToolExecutionResult convert(List<Map<String, Object>> content, boolean isError) {
        StringJoiner text = new StringJoiner("\n");
        if (content != null) {
            for (Map<String, Object> item : content) {
                if (item == null) continue;
                Object type = item.get("type");
                if ("text".equals(type)) {
                    text.add(value(item.get("text")));
                } else if ("resource".equals(type)) {
                    text.add(renderResource(item.get("resource")));
                } else if ("resource_link".equals(type)) {
                    text.add("[resource: " + value(item.get("uri")) + "]");
                } else if ("image".equals(type) || "audio".equals(type)) {
                    text.add("[" + type + ", mime type: " + value(item.get("mimeType")) + "]");
                } else {
                    log.warning("Unrecognized MCP content item of type '" + type + "'; reporting it in place.");
                    text.add("[unsupported content type: " + value(type) + "]");
                }
            }
        }
        return ToolExecutionResult.builder()
                .isError(isError)
                .resultText(text.toString())
                .build();
    }

    /** An embedded resource: text contents go through verbatim, binary ones are described. */
    private static String renderResource(Object resource) {
        if (!(resource instanceof Map<?, ?> contents)) {
            return "[resource]";
        }
        Object text = contents.get("text");
        if (text != null) {
            return value(text);
        }
        Object blob = contents.get("blob");
        return "[binary resource, uri: " + value(contents.get("uri"))
                + ", mime type: " + value(contents.get("mimeType"))
                + ", size: " + (blob instanceof String base64 ? base64DecodedSize(base64) + " bytes" : "unknown")
                + "]";
    }

    private static int base64DecodedSize(String base64) {
        int length = base64.replaceAll("\\s", "").length();
        return length * 3 / 4;
    }

    private static String value(Object o) {
        return (o == null) ? "null" : String.valueOf(o);
    }
}
