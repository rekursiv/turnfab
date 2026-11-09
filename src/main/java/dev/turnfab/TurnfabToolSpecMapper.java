package dev.turnfab;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

import java.util.function.BiFunction;

public class TurnfabToolSpecMapper implements BiFunction<McpClient, ToolSpecification, ToolSpecification> {
    @Override
    public ToolSpecification apply(McpClient mc, ToolSpecification original) {
        ToolSpecification.Builder b = original.toBuilder();

        if (!mc.key().equals("tavily")) {  // tavily tools already prefixed
            switch (original.name()) {
                case "turnfab_read_file" -> b.name(mc.key()+"-read_file");
                case "turnfab_apply_patch" -> b.name(mc.key()+"-apply_patch");
                case "turnfab_list_directory_tree" -> b.name(mc.key()+"-list_directory_tree");
                case "create_new_file" -> b.name(mc.key()+"-create_new_file")
                        .description("""
                        Creates a new file with the given content, or fully replaces an existing file's \
                        contents when overwrite is true.
                        Use this to create a file or to replace a whole file's contents in one step.
                        Use apply_patch instead for partial or surgical edits to an existing file.
                        Paths are relative to the project root.
                        """);
                default -> b.name(mc.key()+"-"+original.name());
            }
        }
        return b.build();
    }
}
