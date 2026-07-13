package com.qa.flaky.model;

import java.time.Instant;

/**
 * One execution of one test case inside one Jenkins build.
 */
public record TestExecution(
        String className,
        String testName,
        TestStatus status,
        double durationSeconds,
        int buildNumber,
        Instant timestamp,
        String errorDetails) {

    /** Stable identity of a test case across builds. */
    public String testId() {
        return className + "." + testName;
    }
}
