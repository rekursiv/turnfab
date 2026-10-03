package dev.turnfab;

public class TokenStatsEvent {
    private int totalTokens;
    private float percentTokensUsed;
    private float tokensPerSec;

    public TokenStatsEvent(int totalTokens, float percentTokensUsed, float tokensPerSec) {
        this.totalTokens = totalTokens;
        this.percentTokensUsed = percentTokensUsed;
        this.tokensPerSec = tokensPerSec;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public float getPercentTokensUsed() {
        return percentTokensUsed;
    }

    public float getTokensPerSec() {
        return tokensPerSec;
    }

}
