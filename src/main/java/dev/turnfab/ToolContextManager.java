package dev.turnfab;

import com.google.inject.Inject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ToolContextManager {

    @Inject private Logger log;
    @Inject private DynamicMcpToolProvider toolProvider;

    private StringBuilder prompt = new StringBuilder();
    private StringBuilder promptSummary = new StringBuilder();

    public void resetPrompt() {
        prompt = new StringBuilder();
        promptSummary = new StringBuilder();
    }

    public String getPrompt() {
        return prompt.toString();
    }

    public String getPromptSummary() {
        return promptSummary.toString();
    }

    public String getPreview(List<ToolContextConfig.Context> context) {
        StringBuilder sb = new StringBuilder();
        if (context.isEmpty()) return "context is empty.";
        for (ToolContextConfig.Context block : context) {
            if (block.text != null) {
                sb.append(block.text);
                sb.append("\n");
            } else if (block.toolName!=null) {
                sb.append("> ");
                sb.append(block.mcpName);
                sb.append(" ");
                sb.append(block.toolName);
                sb.append(" ");
                if (block.arg1!=null) sb.append(block.arg1);
                if (block.arg2!=null) sb.append(block.arg2);
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public void buildPrompt(List<ToolContextConfig.Context> context) {
        for (ToolContextConfig.Context block : context) {
            if (block.text!=null) {
                prompt.append(block.text);
                promptSummary.append(block.text);
            } else if (block.toolName!=null) {
                callTool(block);
            }
        }
    }




    private void callTool(ToolContextConfig.Context block) {
        McpClient mcpClient = toolProvider.getMcpClientByKey(block.mcpName);
        if (mcpClient == null) {
            log.warning("Cannot find MCP Client for " + block.mcpName);
            return;
        }
        switch (block.toolName) {
            case "allTabs":
                readAllTabs(mcpClient);
                break;
            case "curTab":
                readActiveTab(mcpClient);
                break;
            case "dirTree":
                String dirPath = block.arg1;
                int depth =  Integer.parseInt(block.arg2);
                listDirTree(mcpClient, dirPath, depth);
                break;
            default:
                log.warning("Unknown Tool Name " + block.toolName);
        }
    }


    private void listDirTree(McpClient mcpClient, String dirPath, int maxDepth) {
        if (mcpClient == null) {
            appendBoth("\n\n*** MCP Client is null ***\n\n");
            return;
        }

        appendBoth(mcpClient.key());
        appendBoth("-list_directory_tree: directoryPath = ");
        appendBoth(dirPath);
        appendBoth(", max_depth = "+maxDepth);
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

        promptSummary.append(":  ");
        promptSummary.append(rmap.get("tree").length());
        promptSummary.append(" characters");
        promptSummary.append("\n");
    }

    private void readAllTabs(McpClient mcpClient) {
        if (mcpClient == null) {
            appendBoth("\n\n*** MCP Client is null ***\n\n");
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
                readFile(mcpClient, file.toString().replace("\\", "/"));
            }
        }
    }

    private void readActiveTab(McpClient mcpClient) {
        if (mcpClient == null) {
            appendBoth("\n\n*** MCP Client is null ***\n\n");
            return;
        }

        String activeFile = getActiveTabPath(mcpClient);
        readFile(mcpClient, activeFile);
    }




    private String getActiveTabPath(McpClient mcpClient) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("get_all_open_file_paths")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> rmap = (Map<String, Object>) res.result();
        return (String) rmap.get("activeFilePath").toString().replace("\\", "/");
    }

    private void readFile(McpClient mcpClient, String filePath) {
        if (mcpClient == null) {
            appendBoth("\n\n*** MCP Client is null ***\n\n");
            return;
        }

//        StringBuilder prompt = new StringBuilder();
        appendBoth(mcpClient.key());
        appendBoth("-read_file: file_path = ");
        appendBoth(filePath);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("read_file")
                .arguments("{\"file_path\": \""+filePath+"\"}")
                .build();
        ToolExecutionResult res = mcpClient.executeTool(request);
        prompt.append("\n```\n");
        prompt.append(res.resultText());
        prompt.append("\n```\n\n");

        promptSummary.append(":  ");
        promptSummary.append(res.resultText().length());
        promptSummary.append(" characters");
        promptSummary.append("\n");

    }

    private void appendBoth(String txt) {
        prompt.append(txt);
        promptSummary.append(txt);
    }



    /////////////
    ///
    ///

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
