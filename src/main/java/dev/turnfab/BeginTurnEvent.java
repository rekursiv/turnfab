package dev.turnfab;

public class BeginTurnEvent {
    private String userPrompt;
    private String userPromptSummary;

    public BeginTurnEvent(String userPrompt) {
        this(userPrompt, null);
    }

    public BeginTurnEvent(String userPrompt, String userPromptSummary) {
        this.userPrompt = userPrompt;
        this.userPromptSummary = userPromptSummary;
    }

    public String getUserPrompt() {
        return userPrompt;
    }
    public String getUserPromptSummary() {
        return userPromptSummary;
    }
}
