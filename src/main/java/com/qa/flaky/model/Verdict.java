package com.qa.flaky.model;

/**
 * Rule-based classification of a test case. Only FLAKY tests reach the flaky section
 * of the report; ALWAYS_FAILING is deliberately separated so a genuinely broken test
 * is never mislabelled as flaky.
 */
public enum Verdict {
    STABLE("Passed on every conclusive run"),
    FLAKY("Alternates between pass and fail without a code-change explanation"),
    ALWAYS_FAILING("Failed on every conclusive run — consistently broken, not flaky"),
    NEWLY_BROKEN("Passed historically, then failed continuously up to the latest build — likely a real regression"),
    INSUFFICIENT_DATA("Too few conclusive runs to judge");

    private final String description;

    Verdict(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
