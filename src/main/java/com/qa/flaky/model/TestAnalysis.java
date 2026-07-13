package com.qa.flaky.model;

import java.util.List;

/**
 * Deterministic analysis of one test case.
 *
 * @param statusTimeline  oldest-to-newest outcome string, e.g. "PFPPFP" — the raw evidence
 * @param flipCount       number of pass->fail or fail->pass transitions between adjacent runs
 * @param flakinessScore  0-100, computed by {@code FlakyTestAnalyzer}; drives {@code severity}
 * @param indicators      human-readable reasons that explain the score and verdict
 * @param failedBuilds    build numbers where this test failed, for drill-down in Jenkins
 */
public record TestAnalysis(
        String testId,
        String className,
        String testName,
        Verdict verdict,
        Severity severity,
        double flakinessScore,
        int totalRuns,
        int passCount,
        int failCount,
        int skipCount,
        double passRate,
        double failRate,
        int flipCount,
        double flipRate,
        int recentFlipCount,
        int consecutiveTrailingFailures,
        double avgDurationSeconds,
        double durationStdDevSeconds,
        double durationCoefficientOfVariation,
        String statusTimeline,
        List<Integer> failedBuilds,
        List<String> indicators,
        String sampleError) {

    public boolean isFlaky() {
        return verdict == Verdict.FLAKY;
    }
}
