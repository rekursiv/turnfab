package dev.turnfab;

public class TokenStatsEvent {
    private int totalTokens;
    private int usedTokens;
    private float percentTokensUsed;
    private float tokensPerSec;

    public TokenStatsEvent(int totalTokens, int usedTokens, float percentTokensUsed, float tokensPerSec) {
        this.totalTokens = totalTokens;
        this.usedTokens = usedTokens;
        this.percentTokensUsed = percentTokensUsed;
        this.tokensPerSec = tokensPerSec;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public int getUsedTokens() { return usedTokens; }

    public float getPercentTokensUsed() {
        return percentTokensUsed;
    }

    public float getTokensPerSec() {
        return tokensPerSec;
    }

}
