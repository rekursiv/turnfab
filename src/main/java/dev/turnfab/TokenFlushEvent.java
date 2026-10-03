package dev.turnfab;

public class TokenFlushEvent {
    private boolean endOfTurn;

    public TokenFlushEvent(boolean endOfTurn) {
        this.endOfTurn = endOfTurn;
    }

    public boolean isEndOfTurn() {
        return endOfTurn;
    }
}
