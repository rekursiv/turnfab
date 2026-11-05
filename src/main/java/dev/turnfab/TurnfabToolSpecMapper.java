package dev.turnfab;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

import java.util.function.BiFunction;

public class TurnfabToolSpecMapper implements BiFunction<McpClient, ToolSpecification, ToolSpecification> {
    @Override
    public ToolSpecification apply(McpClient mc, ToolSpecification original) {
        String newName = mc.key().equals("tavily") ? original.name() : mc.key() + "-" + original.name();
        ToolSpecification.Builder b = original.toBuilder().name(newName);

        if (!mc.key().equals("tavily") && !mc.key().equals("github")) {
            switch (original.name()) {
                case "read_file" -> b.name(mc.key()+"-read_file")
                        .description("""
                        Reads a file in the project directory or from any project dependency or other project source root.
                        Can read sources inside Jar/Jrt files and decompile Java class files inside Jar/Jrt files or on disk.
                        Returns numbered lines (1-indexed) as text.

                        Truncation: this tool does NOT return a contiguous slice when it has to cut.
                        It returns floor(limit/2) contiguous lines starting at offset, and then it JUMPS to the
                        final ceil(limit/2) lines of the file. The "N lines truncated" marker is the gap between
                        those two blocks, not a note about the requested range. The tail block is anchored to the
                        end of the file, not to the window that was asked for, so a read from the middle of a
                        large file shows a short leading block followed by the last few lines of the file. Those
                        are two unrelated regions; never read them as one continuous passage, and never conclude
                        that the file ends where the leading block stops.
                        When offset is close enough to the end of the file, the two blocks touch and you get a
                        single continuous range with no marker.

                        Working with this:
                        - Ask for limit = 2x the lines you actually need, and read only the leading block.
                        - When the file is small enough to need no truncation, limit is just a line count.
                        - limit=1 returns the final line plus the count of omitted lines, which is a cheap way
                          to learn the total line count of a file.
                        """);
                case "create_new_file" -> b.name(mc.key()+"-create_new_file")
                        .description("""
                        Creates a new file with the given content, or fully replaces an existing file's \
                        contents when overwrite is true.
                        Use this to create a file or to replace a whole file's contents in one step.
                        Use apply_patch instead for partial or surgical edits to an existing file.
                        Paths are relative to the project root.
                        """);
                case "apply_patch" -> b.name(mc.key()+"-apply_patch")
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
