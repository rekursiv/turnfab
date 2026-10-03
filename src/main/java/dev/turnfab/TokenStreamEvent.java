package dev.turnfab;

public class TokenStreamEvent {

    private String chunk;

    public TokenStreamEvent(String chunk) {
        this.chunk = chunk;
    }

    public String getChunk() {
        return chunk;
    }
}
