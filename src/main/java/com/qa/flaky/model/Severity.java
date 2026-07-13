package com.qa.flaky.model;

/**
 * Severity buckets derived from the flakiness score. Thresholds are fixed constants
 * so the same history always yields the same severity.
 */
public enum Severity {
    CRITICAL(70),
    HIGH(50),
    MEDIUM(30),
    LOW(0),
    NONE(-1);

    private final int minScore;

    Severity(int minScore) {
        this.minScore = minScore;
    }

    public static Severity fromScore(double score) {
        if (score >= CRITICAL.minScore) return CRITICAL;
        if (score >= HIGH.minScore) return HIGH;
        if (score >= MEDIUM.minScore) return MEDIUM;
        return LOW;
    }
}
