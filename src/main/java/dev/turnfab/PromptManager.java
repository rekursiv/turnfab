package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class PromptManager {

    @Inject private Logger log;
    @Inject private DynamicMcpToolProvider toolProvider;

    private StringBuilder prompt = new StringBuilder();


    public void buildPrompt() {
        resetPrompt();
        buildLc4jPrompt();
        buildMsPrompt();
        buildPromptFromTabFiles("turnfab");
    }

    public void buildPromptFromTabFiles(String mcpName) {
        readAllTabs(toolProvider.getMcpClientByKey(mcpName));
    }

    private void buildLc4jPrompt() {
        prompt.append("Full searchable source code for LangChain4j is available with lc4j-* tools.\n\n");
        prompt.append("LangChain4j documentation: ");
        listDirTree(toolProvider.getMcpClientByKey("lc4j"), "docs/docs", 3);
    }

    private void buildMsPrompt() {
        prompt.append("Documentation for markstream-vue:");
        listDirTree(toolProvider.getMcpClientByKey("markstream"), "docs", 2);
        prompt.append("Location of files that render markdown in my app (turnfab):");  // dirPath markstream-page, depth: 2
        listDirTree(toolProvider.getMcpClientByKey("turnfab"), "markstream-page", 2);
    }




    public void resetPrompt() {
        prompt = new StringBuilder();
    }

    public String getPrompt() {
        return prompt.toString();
    }





    public void listDirTree(McpClient mcpClient, String dirPath, int maxDepth) {
        if (mcpClient == null) {
            prompt.append("\n\n*** MCP Client is null ***\n\n");
            return;
        }
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
    }

    public void readAllTabs(McpClient mcpClient) {
        if (mcpClient == null) {
            prompt.append("\n\n*** MCP Client is null ***\n\n");
            return;
        }
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("get_all_open_file_paths")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> rmap = (Map<String, Object>) res.result();
        List<?> openFiles = (List<?>) rmap.get("openFiles");
        if (openFiles != null) {
            for (Object file : openFiles) {
                prompt.append(readFile(mcpClient, file.toString().replace("\\", "/")));
                prompt.append('\n');
            }
        }
    }



    public String readActiveTab(McpClient mcpClient) {
        if (mcpClient == null) {
            return "MCP Client is null";
        }
        String activeFile = getActiveTabPath(mcpClient);
        System.out.println(">>>>  activeFile: " + activeFile);
        return readFile(mcpClient, activeFile);
    }

    public String getActiveTabPath(McpClient mcpClient) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("get_all_open_file_paths")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> rmap = (Map<String, Object>) res.result();
        return (String) rmap.get("activeFilePath").toString().replace("\\", "/");
    }

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

    public String getAllTabPaths(McpClient mcpClient) {
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
