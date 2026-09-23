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
                case "create_new_file" -> b.name("main_project-create_new_file")
                        .description("""
                        Creates a new file with the given content, or fully replaces an existing file's \
                        contents when overwrite is true.
                        Use this to create a file or to replace a whole file's contents in one step.
                        Use apply_patch instead for partial or surgical edits to an existing file.
                        Paths are relative to the project root.
                        """);
                case "apply_patch" -> b.name("main_project-apply_patch")
                        .description("""
                        Makes surgical edits to existing files, and can also delete or rename/move files.
                        To create a file or replace a whole file's contents, use create_new_file instead.
                        Use the *** apply_patch format.
                        A patch begins with a line "*** Begin Patch" and ends with a line "*** End Patch".
                        Between them, list one or more operations:
                          *** Add File: <path>     create a new file; every following line starts with "+"
                          *** Update File: <path>  edit an existing file; group changes into one or more hunks
                          *** Delete File: <path>  delete a file; no patch lines follow
                          *** Move to: <path>      optional; place directly after an "Update File" line to also rename/move the file
                        Each hunk begins with a line containing only "@@" (line numbers are optional and ignored),
                        followed by lines each prefixed with:
                          " " (a single space)  unchanged context line, copied exactly from the file
                          "-"                  a line to remove
                          "+"                  a line to add
                        Context lines must match the current file content exactly (including indentation),
                        or the hunk fails with "Hunk context not found". Read the file first, then include
                        enough surrounding context so the hunk matches uniquely.
                        Paths must be relative to the project root and stay inside the project directory.
                        Example:
                        *** Begin Patch
                        *** Update File: src/Foo.java
                        @@
                         public class Foo {
                        -    int x = 1;
                        +    int x = 2;
                         }
                        *** End Patch
                        """);
                default -> {}
            }
        }
        return b.build();
    }
}
