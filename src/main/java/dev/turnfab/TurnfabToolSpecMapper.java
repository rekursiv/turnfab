package dev.turnfab;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

import java.util.function.BiFunction;

public class TurnfabToolSpecMapper implements BiFunction<McpClient, ToolSpecification, ToolSpecification> {
    @Override
    public ToolSpecification apply(McpClient mc, ToolSpecification original) {
        String newName = mc.key().equals("tavily") ? original.name() : mc.key() + "-" + original.name();
        ToolSpecification.Builder b = original.toBuilder().name(newName);

        if (mc.key().equals("main_project")) {
            switch (original.name()) {
                case "apply_patch" -> b.name("main_project-apply_patch")
                        .description("""
*** Begin Patch, then *** Update File: <path> (or *** Add File: / *** Delete File:), hunk headers @@, \
context lines prefixed with a space, removed lines with -, added lines with +, ending with *** End Patch
""");
                default -> {}
            }
        }
        return b.build();
    }
}