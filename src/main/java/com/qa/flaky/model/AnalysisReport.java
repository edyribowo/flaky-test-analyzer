package com.qa.flaky.model;

import java.time.Instant;
import java.util.List;

/**
 * The full deterministic report: suite-level health plus per-test analyses.
 * This object is what the future AI module will consume as its input.
 */
public record AnalysisReport(
        String jobName,
        String jenkinsUrl,
        Instant generatedAt,
        int buildsAnalyzed,
        int firstBuildNumber,
        int lastBuildNumber,
        int totalTests,
        int flakyCount,
        int alwaysFailingCount,
        int newlyBrokenCount,
        int stableCount,
        double suiteHealthScore,
        List<TestAnalysis> flakyTests,
        List<TestAnalysis> allTests) {

    /** Flaky tests ordered worst-first — the order used by every report writer. */
    public List<TestAnalysis> flakyBySeverity() {
        return flakyTests.stream()
                .sorted((a, b) -> Double.compare(b.flakinessScore(), a.flakinessScore()))
                .toList();
    }
}
