package dev.turnfab;

public class SessionSetupEvent {
    private final String name;
    private final SetupConfig setupConfig;
    private final boolean loadTools;

    public SessionSetupEvent(String name, SetupConfig setupConfig, boolean loadTools) {
        this.name = name;
        this.setupConfig = setupConfig;
        this.loadTools = loadTools;
    }
    public String getName() {
        return name;
    }
    public SetupConfig getSetupConfig() {
        return setupConfig;
    }
    public boolean isLoadTools() {
        return loadTools;
    }
}
