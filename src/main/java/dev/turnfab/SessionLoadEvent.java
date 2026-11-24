package dev.turnfab;

public class SessionLoadEvent {
    private final String name;
    private final String path;
    private final int turns;

    public SessionLoadEvent(String name, String path, int turns) {
        this.name = name;
        this.path = path;
        this.turns = turns;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public int getTurns() {
        return turns;
    }
}
