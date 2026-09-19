package dev.turnfab;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.Map;

public class PromptManager {

    public String buildPrompt(McpClient mcpClient) {

        if (mcpClient==null) {
            return "MCP Client is null";
        }

        StringBuilder prompt = new StringBuilder();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("get_all_open_file_paths")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        Map<String, String> rmap = (Map<String, String>) res.result();
        System.out.println(rmap.get("activeFilePath").replace("\\", "/"));

        request = ToolExecutionRequest.builder()
                .name("read_file")
                .arguments("{\"file_path\": \"src/main/java/dev/turnfab/PromptManager.java\"}")
                .build();
        res = mcpClient.executeTool(request);
        prompt.append("\n```"+res.resultText()+"\n```\n\n");
/*
        request = ToolExecutionRequest.builder()
                .name("list_directory_tree")
                .arguments("{\"directoryPath\": \"langchain4j/docs/docs\"}")
                .build();
        res = mcpClient.executeTool(request);
        prompt.append("\n"+res.resultText()+"\n\n\n");
*/

        return prompt.toString();

    }

}
