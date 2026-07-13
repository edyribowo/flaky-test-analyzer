package com.qa.flaky.model;

/**
 * Normalised test outcome. Jenkins reports FIXED/REGRESSION as pass/fail variants,
 * and JUnit XML carries error/failure/skipped markers; both are mapped onto this enum.
 */
public enum TestStatus {
    PASSED,
    FAILED,
    SKIPPED;

    public boolean isPassed() {
        return this == PASSED;
    }

    public boolean isFailed() {
        return this == FAILED;
    }

    /** Skipped runs carry no signal about flakiness and are excluded from flip analysis. */
    public boolean isConclusive() {
        return this != SKIPPED;
    }

    public static TestStatus fromJenkins(String raw) {
        if (raw == null) {
            return SKIPPED;
        }
        return switch (raw.trim().toUpperCase()) {
            case "PASSED", "FIXED" -> PASSED;
            case "FAILED", "REGRESSION", "ERROR" -> FAILED;
            default -> SKIPPED;
        };
    }
}
