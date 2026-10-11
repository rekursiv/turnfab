package dev.turnfab;

public class SendPromptEvent {
    private String prompt;

    SendPromptEvent(String prompt) {
        this.prompt = prompt;
    }

    public String getPrompt() {
        return prompt;
    }
}
