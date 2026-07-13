package com.qa.flaky.analyzer;

import com.qa.flaky.config.AnalyzerConfig;
import com.qa.flaky.model.AnalysisReport;
import com.qa.flaky.model.BuildInfo;
import com.qa.flaky.model.Severity;
import com.qa.flaky.model.TestAnalysis;
import com.qa.flaky.model.TestExecution;
import com.qa.flaky.model.TestStatus;
import com.qa.flaky.model.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlakyTestAnalyzerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-13T00:00:00Z");

    private final FlakyTestAnalyzer analyzer =
            new FlakyTestAnalyzer(AnalyzerConfig.builder("regression").build());

    @Test
    void alternatingPassFailIsFlaky() {
        TestAnalysis result = analyze("PFPFPF");

        assertEquals(Verdict.FLAKY, result.verdict());
        assertEquals(5, result.flipCount());
        assertEquals("PFPFPF", result.statusTimeline());
        assertTrue(result.flakinessScore() >= 70,
                "a perfectly alternating test is the worst case, got " + result.flakinessScore());
        assertEquals(Severity.CRITICAL, result.severity());
    }

    @Test
    void alwaysPassingIsStable() {
        TestAnalysis result = analyze("PPPPPP");

        assertEquals(Verdict.STABLE, result.verdict());
        assertEquals(0.0, result.flakinessScore());
        assertEquals(Severity.NONE, result.severity());
    }

    @Test
    void alwaysFailingIsNotFlaky() {
        TestAnalysis result = analyze("FFFFFF");

        assertEquals(Verdict.ALWAYS_FAILING, result.verdict());
        assertEquals(0.0, result.flakinessScore());
    }

    @Test
    void passingThenFailingContinuouslyIsARegressionNotFlakiness() {
        TestAnalysis result = analyze("PPPFFF");

        assertEquals(Verdict.NEWLY_BROKEN, result.verdict(),
                "one unbroken failure streak at the newest end is a regression");
        assertEquals(3, result.consecutiveTrailingFailures());
        assertEquals(0.0, result.flakinessScore());
    }

    @Test
    void trailingFailuresBelowStreakThresholdStillCountAsFlaky() {
        // Only 2 trailing failures: too short to call a regression, and the test passed after
        // failing earlier — that is intermittent behaviour.
        TestAnalysis result = analyze("PFPPFF");

        assertEquals(Verdict.FLAKY, result.verdict());
    }

    @Test
    void singleFailureAmongPassesIsFlaggedButLowSeverity() {
        TestAnalysis result = analyze("PPPPFPPPPP");

        assertEquals(Verdict.FLAKY, result.verdict());
        assertTrue(result.flakinessScore() < 30,
                "a lone failure among many passes should rank low, got " + result.flakinessScore());
        assertEquals(Severity.LOW, result.severity());
        assertTrue(result.indicators().stream().anyMatch(i -> i.contains("one-off")));
    }

    @Test
    void skippedRunsAreExcludedFromFlipAnalysis() {
        TestAnalysis result = analyze("PSSSPP");

        assertEquals(Verdict.STABLE, result.verdict(), "skips carry no pass/fail signal");
        assertEquals(0, result.flipCount(), "P->S->P must not count as two flips");
        assertEquals(3, result.skipCount());
        assertEquals(6, result.totalRuns());
        assertEquals(3, result.passCount());
    }

    @Test
    void skipsDoNotCountTowardTheMinimumRunThreshold() {
        // Only 2 conclusive runs among 5 executions — not enough history to judge.
        assertEquals(Verdict.INSUFFICIENT_DATA, analyze("PSSSP").verdict());
    }

    @Test
    void tooFewRunsYieldsNoVerdict() {
        TestAnalysis result = analyze("PF");

        assertEquals(Verdict.INSUFFICIENT_DATA, result.verdict());
    }

    @Test
    void ratesAndFailedBuildsAreReported() {
        TestAnalysis result = analyze("PFPF");   // builds 1..4

        assertEquals(0.5, result.passRate());
        assertEquals(0.5, result.failRate());
        assertEquals(List.of(2, 4), result.failedBuilds());
    }

    @Test
    void unstableDurationRaisesTheScore() {
        List<BuildInfo> steady = buildsFor("PFPFPF", new double[]{1.0, 1.0, 1.0, 1.0, 1.0, 1.0});
        List<BuildInfo> erratic = buildsFor("PFPFPF", new double[]{0.5, 9.0, 0.6, 8.5, 0.4, 9.5});

        double steadyScore = only(analyzer.analyze(steady, FIXED_NOW)).flakinessScore();
        double erraticScore = only(analyzer.analyze(erratic, FIXED_NOW)).flakinessScore();

        assertTrue(erraticScore > steadyScore,
                "erratic durations indicate timing flakiness: " + erraticScore + " vs " + steadyScore);
    }

    @Test
    void analysisIsDeterministic() {
        List<BuildInfo> builds = buildsFor("PFPFPF", null);

        AnalysisReport first = analyzer.analyze(builds, FIXED_NOW);
        AnalysisReport second = analyzer.analyze(builds, FIXED_NOW);

        assertEquals(first.flakyBySeverity().get(0).flakinessScore(),
                second.flakyBySeverity().get(0).flakinessScore());
        assertEquals(first.suiteHealthScore(), second.suiteHealthScore());
    }

    @Test
    void suiteHealthDropsWhenTestsAreFlaky() {
        AnalysisReport healthy = analyzer.analyze(buildsFor("PPPPPP", null), FIXED_NOW);
        AnalysisReport unhealthy = analyzer.analyze(buildsFor("PFPFPF", null), FIXED_NOW);

        assertEquals(100.0, healthy.suiteHealthScore());
        assertTrue(unhealthy.suiteHealthScore() < 100.0);
        assertNotEquals(healthy.flakyCount(), unhealthy.flakyCount());
    }

    @Test
    void reportSeparatesFlakyFromConsistentlyBroken() {
        List<BuildInfo> builds = new ArrayList<>();
        String flakyTimeline = "PFPFPF";
        String brokenTimeline = "FFFFFF";

        for (int i = 0; i < flakyTimeline.length(); i++) {
            int buildNumber = i + 1;
            builds.add(new BuildInfo(buildNumber, "UNSTABLE", FIXED_NOW, "url", List.of(
                    execution("LoginTest", "flakyLogin", flakyTimeline.charAt(i), buildNumber, 1.0),
                    execution("LoginTest", "brokenLogin", brokenTimeline.charAt(i), buildNumber, 1.0))));
        }

        AnalysisReport report = analyzer.analyze(builds, FIXED_NOW);

        assertEquals(2, report.totalTests());
        assertEquals(1, report.flakyCount());
        assertEquals(1, report.alwaysFailingCount());
        assertEquals("LoginTest.flakyLogin", report.flakyBySeverity().get(0).testId());
    }

    // --- helpers -------------------------------------------------------------

    /** Analyses a single test whose outcome per build is given by a P/F/S timeline. */
    private TestAnalysis analyze(String timeline) {
        return only(analyzer.analyze(buildsFor(timeline, null), FIXED_NOW));
    }

    private TestAnalysis only(AnalysisReport report) {
        assertEquals(1, report.allTests().size());
        return report.allTests().get(0);
    }

    /** One build per character, build numbers 1..n, oldest first. */
    private List<BuildInfo> buildsFor(String timeline, double[] durations) {
        List<BuildInfo> builds = new ArrayList<>();
        for (int i = 0; i < timeline.length(); i++) {
            int buildNumber = i + 1;
            double duration = durations == null ? 1.0 : durations[i];
            builds.add(new BuildInfo(buildNumber, "SUCCESS", FIXED_NOW, "url",
                    List.of(execution("CheckoutTest", "addToCart", timeline.charAt(i), buildNumber, duration))));
        }
        return builds;
    }

    private TestExecution execution(String className, String testName, char status, int buildNumber, double duration) {
        TestStatus testStatus = switch (status) {
            case 'P' -> TestStatus.PASSED;
            case 'F' -> TestStatus.FAILED;
            default -> TestStatus.SKIPPED;
        };
        String error = testStatus.isFailed() ? "expected true but was false" : null;
        return new TestExecution(className, testName, testStatus, duration, buildNumber, FIXED_NOW, error);
    }
}
