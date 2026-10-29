package dev.turnfab;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.List;

/**
 * Counts reasoning content that is carried in assistant messages, and optionally counts the token cost of the
 * tool definitions that are sent alongside the conversation.
 *
 * <p>Text counting is delegated to the wrapped estimator. Only {@code estimateTokenCountInMessages()} adds the
 * cached tool overhead, because tools are a request-level overhead rather than part of any single message.</p>
 */
public final class ReasoningAndToolAwareTokenCountEstimator implements TokenCountEstimator {

    private final TokenCountEstimator textEstimator;

    private volatile List<ToolSpecification> toolSpecifications = List.of();
    private volatile int toolTokenCount = 0;

    public ReasoningAndToolAwareTokenCountEstimator(TokenCountEstimator textEstimator) {
        this.textEstimator = textEstimator;
    }

    /**
     * Replaces the tool definition set used when estimating an entire message window.
     */
    public synchronized void setToolSpecifications(List<ToolSpecification> tools) {
        List<ToolSpecification> next;

        try {
            next = tools == null ? List.of() : List.copyOf(tools);
        } catch (RuntimeException ignored) {
            next = List.of();
        }

        toolSpecifications = next;
        toolTokenCount = estimateTools(next);
    }

    public synchronized void clearToolSpecifications() {
        setToolSpecifications(List.of());
    }

    public int currentToolTokenCount() {
        return toolTokenCount;
    }

    public List<ToolSpecification> currentToolSpecifications() {
        return toolSpecifications;
    }

    @Override
    public int estimateTokenCountInText(String text) {
        return textEstimator.estimateTokenCountInText(text);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        return textEstimator.estimateTokenCountInMessage(message) + thinkingTokens(message);
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int total = textEstimator.estimateTokenCountInMessages(messages);

        for (ChatMessage message : messages) {
            total += thinkingTokens(message);
        }

        return total + toolTokenCount;
    }

    private int thinkingTokens(ChatMessage message) {
        if (message instanceof AiMessage aiMessage && aiMessage.thinking() != null) {
            return textEstimator.estimateTokenCountInText(aiMessage.thinking());
        }

        return 0;
    }

    private int estimateTools(List<ToolSpecification> tools) {
        if (tools.isEmpty()) {
            return 0;
        }

        try {
            return textEstimator.estimateTokenCountInText(Json.toJson(tools));
        } catch (RuntimeException e) {
            return textEstimator.estimateTokenCountInText(String.valueOf(tools));
        }
    }
}
