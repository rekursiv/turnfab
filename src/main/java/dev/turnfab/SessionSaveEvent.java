package dev.turnfab;

public class SessionSaveEvent {
    private final String sessionName;
    private final boolean firstSave;

    public SessionSaveEvent(String sessionName, boolean firstSave) {
        this.sessionName = sessionName;
        this.firstSave = firstSave;
    }

    public String getSessionName() {
        return sessionName;
    }

    public boolean isFirstSave() {
        return firstSave;
    }

}
