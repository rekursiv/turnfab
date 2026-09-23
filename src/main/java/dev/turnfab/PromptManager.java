package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class PromptManager {

    @Inject
    private Logger log;

    public String readFile(McpClient mcpClient, String filePath) {
        if (mcpClient == null) {
            return "MCP Client is null";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append(mcpClient.key());
        prompt.append("-read_file: file_path = ");
        prompt.append(filePath);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("read_file")
                .arguments("{\"file_path\": \""+filePath+"\"}")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        prompt.append("\n```\n");
        prompt.append(res.resultText());
        prompt.append("\n```\n\n");

        return prompt.toString();
    }

    public String listDirTree(McpClient mcpClient, String dirPath, int maxDepth) {
        if (mcpClient == null) {
            return "MCP Client is null";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append(mcpClient.key());
        prompt.append("-list_directory_tree: directoryPath = ");
        prompt.append(dirPath);
        prompt.append(", max_depth = ");
        prompt.append(maxDepth);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("list_directory_tree")
                .arguments("{\"directoryPath\": \""+dirPath+"\", \"maxDepth\": "+maxDepth+"}")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        prompt.append("\n");

        @SuppressWarnings("unchecked")
        Map<String, String> rmap = (Map<String, String>) res.result();
        prompt.append(rmap.get("tree"));
        prompt.append("\n\n");

        return prompt.toString();
    }

    public String getTabs(McpClient mcpClient) {
        if (mcpClient==null) {
            return "MCP Client is null";
        }

        StringBuilder prompt = new StringBuilder();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("get_all_open_file_paths")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> rmap = (Map<String, Object>) res.result();
        String activeFile = (String) rmap.get("activeFilePath");
        List<?> openFiles = (List<?>) rmap.get("openFiles");
        if (openFiles != null) {
            for (Object file : openFiles) {
                prompt.append(file.toString().replace("\\", "/"));
                if (file.toString().equals(activeFile)) prompt.append("   <<<");
                prompt.append('\n');
            }
        }

        return prompt.toString();

    }

}
