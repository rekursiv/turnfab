package dev.turnfab;

public class SessionSetupEvent {
    private final String name;
    private final SetupConfig setupConfig;

    public SessionSetupEvent(String name, SetupConfig setupConfig) {
        this.name = name;
        this.setupConfig = setupConfig;
    }
    public String getName() {
        return name;
    }
    public SetupConfig getSetupConfig() {
        return setupConfig;
    }
}
