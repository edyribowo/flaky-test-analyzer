package com.qa.flaky.model;

import java.time.Instant;
import java.util.List;

/**
 * A Jenkins build together with the test executions harvested from its JUnit report.
 */
public record BuildInfo(
        int number,
        String result,
        Instant timestamp,
        String url,
        List<TestExecution> executions) {
}
