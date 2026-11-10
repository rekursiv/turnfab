package dev.turnfab;

/**
 * Posted by {@link TokenJockey} after it knows the live turn number, before any of that turn's markdown is
 * sent to the view. It tells the rendered page which details blocks belong to the turn it is about to start.
 */
public class MarkstreamTurnEvent {
    private final int turnNumber;

    MarkstreamTurnEvent(int turnNumber) {
        this.turnNumber = turnNumber;
    }

    public int getTurnNumber() {
        return turnNumber;
    }
}
