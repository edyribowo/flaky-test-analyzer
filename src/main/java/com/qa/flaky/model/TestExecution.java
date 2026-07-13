package com.qa.flaky.model;

import java.time.Instant;

/**
 * One execution of one test case inside one Jenkins build.
 *
 * @param errorDetails    single-line failure message, e.g. "expected 10 but was 0"
 * @param errorStackTrace raw stack trace text, used to locate the source of the failure
 */
public record TestExecution(
        String className,
        String testName,
        TestStatus status,
        double durationSeconds,
        int buildNumber,
        Instant timestamp,
        String errorDetails,
        String errorStackTrace) {

    /** Stable identity of a test case across builds. */
    public String testId() {
        return className + "." + testName;
    }
}
