package dev.turnfab;

public class BeginTurnEvent {
    private String systemPrompt;
    private String userPrompt;
    private String userPromptSummary;

    BeginTurnEvent(String userPrompt) {
        this(null, userPrompt, null);
    }

    BeginTurnEvent(String systemPrompt, String userPrompt, String userPromptSummary) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.userPromptSummary = userPromptSummary;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }
    public String getUserPrompt() {
        return userPrompt;
    }
    public String getUserPromptSummary() {
        return userPromptSummary;
    }
}
