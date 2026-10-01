package dev.turnfab;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.HashMap;
import java.util.Map;

public enum QwenMode {
    THINKING (1.0, 0.95, 0.0),
    INSTRUCT (0.7, 0.80, 1.5);

    private static final Map<String, Object> SHARED = Map.of(
            "top_k", 20, "min_p", 0.0, "repetition_penalty", 1.0);

    private final double temperature, topP, presencePenalty;
    QwenMode(double t, double p, double pp) { temperature = t; topP = p; presencePenalty = pp; }

    public OpenAiChatRequestParameters parameters() {
        Map<String, Object> custom = new HashMap<>(SHARED);          // customParameters
        custom.put("chat_template_kwargs",                           //   -> top level
                Map.of("enable_thinking", this == THINKING));     //   -> template kwargs
        return OpenAiChatRequestParameters.builder()
                .temperature(temperature)
                .topP(topP)
                .presencePenalty(presencePenalty)
                .customParameters(custom)
                .build();
    }
}
