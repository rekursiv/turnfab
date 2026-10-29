package dev.turnfab;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs actual prompt tokens reported by the model against the local estimate, one model request at a time.
 * This is deliberately diagnostic only: it never compacts, trims or changes any request.
 */
public final class TokenAuditListener implements ChatModelListener {

    private static final String ESTIMATE_ATTR = TokenAuditListener.class.getName() + ".estimate";

    private final TokenCountEstimator estimator;
    private final Logger log;

    public TokenAuditListener(TokenCountEstimator estimator, Logger log) {
        this.estimator = estimator;
        this.log = log;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        try {
            context.attributes().put(ESTIMATE_ATTR, estimate(context.chatRequest()));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Token audit request estimate failed", e);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            if (!(context.attributes().get(ESTIMATE_ATTR) instanceof Estimate estimate)) {
                return;
            }

            TokenUsage usage = context.chatResponse().metadata().tokenUsage();
            Integer input = usage == null ? null : usage.inputTokenCount();
            Integer output = usage == null ? null : usage.outputTokenCount();

            if (input == null) {
                log.info("TOKEN_AUDIT actualInput=N/A estimatedInput=" + estimate.total());
                return;
            }

            int estimatedInput = estimate.total();
            double ratio = estimatedInput > 0 ? (double) input / estimatedInput : 0.0;

            log.info(
                    "TOKEN_AUDIT" +
                    " actualInput=" + input +
                    " actualOutput=" + output +
                    " estimatedInput=" + estimatedInput +
                    " ratio=" + format(ratio) +
                    " messages=" + context.chatRequest().messages().size() +
                    " messageTextEstimate=" + estimate.messageText +
                    " thinkingMessages=" + estimate.thinkingMessages +
                    " thinkingEstimate=" + estimate.thinking +
                    " toolCount=" + estimate.toolCount +
                    " toolJsonChars=" + estimate.toolJsonChars +
                    " toolEstimate=" + estimate.tools
            );
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Token audit response failed", e);
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        // Attributes are scoped to this request, so there is nothing to clean up.
    }

    private Estimate estimate(ChatRequest request) {
        int messageText = estimator.estimateTokenCountInMessages(request.messages());

        int thinking = 0;
        int thinkingMessages = 0;

        for (ChatMessage message : request.messages()) {
            if (message instanceof AiMessage aiMessage && aiMessage.thinking() != null) {
                thinkingMessages++;
                thinking += estimator.estimateTokenCountInText(aiMessage.thinking());
            }
        }

        List<ToolSpecification> tools = request.toolSpecifications();
        int toolCount = tools == null ? 0 : tools.size();
        int toolsEstimate = 0;
        int toolJsonChars = 0;

        if (tools != null && !tools.isEmpty()) {
            String toolJson;

            try {
                toolJson = Json.toJson(tools);
            } catch (RuntimeException e) {
                toolJson = String.valueOf(tools);
            }

            toolJsonChars = toolJson.length();
            toolsEstimate = estimator.estimateTokenCountInText(toolJson);
        }

        return new Estimate(
                messageText,
                thinking,
                thinkingMessages,
                toolCount,
                toolJsonChars,
                toolsEstimate
        );
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private record Estimate(
            int messageText,
            int thinking,
            int thinkingMessages,
            int toolCount,
            int toolJsonChars,
            int tools
    ) {
        int total() {
            return messageText + thinking + tools;
        }
    }
}
