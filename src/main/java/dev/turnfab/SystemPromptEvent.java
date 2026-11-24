package dev.turnfab;

public class SystemPromptEvent {
    private final String systemPrompt;

    public SystemPromptEvent(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }
}
