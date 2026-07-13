package com.qa.flaky.analyzer;

import com.qa.flaky.config.AnalyzerConfig;
import com.qa.flaky.model.AnalysisReport;
import com.qa.flaky.model.BuildInfo;
import com.qa.flaky.model.Severity;
import com.qa.flaky.model.TestAnalysis;
import com.qa.flaky.model.TestExecution;
import com.qa.flaky.model.TestHistory;
import com.qa.flaky.model.TestStatus;
import com.qa.flaky.model.Verdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Rule-based, deterministic flakiness detection. No AI, no randomness, no wall-clock input:
 * the same build history always produces the same report.
 *
 * <h2>Verdict rules</h2>
 * Applied to the pass/fail sequence of a test, oldest build first (skips excluded):
 * <ol>
 *   <li>fewer than {@code minRunsForVerdict} conclusive runs -> INSUFFICIENT_DATA</li>
 *   <li>no failures -> STABLE</li>
 *   <li>no passes -> ALWAYS_FAILING (consistently broken, never reported as flaky)</li>
 *   <li>all failures form one unbroken block at the newest end, at least
 *       {@link #REGRESSION_STREAK} long -> NEWLY_BROKEN (a real regression, not flakiness)</li>
 *   <li>otherwise the test both passed and failed while alternating -> FLAKY</li>
 * </ol>
 *
 * <h2>Flakiness score (0-100)</h2>
 * Four weighted, independently explainable components:
 * <pre>
 *   flip      (45) = flipRate                       -- how often the outcome changes between builds
 *   balance   (25) = 1 - |passRate - 0.5| * 2       -- peaks at a 50/50 pass/fail split
 *   volume    (15) = min(failCount, 5) / 5          -- one-off failures score lower than repeated ones
 *   duration  (15) = min(durationCV, 1)             -- unstable runtimes correlate with timing flakiness
 * </pre>
 * Severity buckets: >=70 CRITICAL, >=50 HIGH, >=30 MEDIUM, else LOW.
 */
public class FlakyTestAnalyzer {

    /** Failures at the newest end of the timeline at or above this streak read as a regression. */
    static final int REGRESSION_STREAK = 3;

    /** Window, in most recent conclusive runs, used for the "still flipping recently" indicator. */
    static final int RECENT_WINDOW = 5;

    private static final double WEIGHT_FLIP = 45.0;
    private static final double WEIGHT_BALANCE = 25.0;
    private static final double WEIGHT_VOLUME = 15.0;
    private static final double WEIGHT_DURATION = 15.0;

    private final AnalyzerConfig config;

    public FlakyTestAnalyzer(AnalyzerConfig config) {
        this.config = config;
    }

    public AnalysisReport analyze(List<BuildInfo> builds, Instant generatedAt) {
        if (builds.isEmpty()) {
            return new AnalysisReport(config.jobName(), config.jenkinsUrl(), generatedAt,
                    0, 0, 0, 0, 0, 0, 0, 0, 100.0, List.of(), List.of());
        }

        List<TestAnalysis> analyses = groupByTest(builds).values().stream()
                .map(this::analyzeTest)
                .sorted((a, b) -> Double.compare(b.flakinessScore(), a.flakinessScore()))
                .toList();

        List<TestAnalysis> flaky = analyses.stream().filter(TestAnalysis::isFlaky).toList();
        int alwaysFailing = count(analyses, Verdict.ALWAYS_FAILING);
        int newlyBroken = count(analyses, Verdict.NEWLY_BROKEN);
        int stable = count(analyses, Verdict.STABLE);

        int minBuild = builds.stream().mapToInt(BuildInfo::number).min().orElse(0);
        int maxBuild = builds.stream().mapToInt(BuildInfo::number).max().orElse(0);

        return new AnalysisReport(
                config.jobName(),
                config.jenkinsUrl(),
                generatedAt,
                builds.size(),
                minBuild,
                maxBuild,
                analyses.size(),
                flaky.size(),
                alwaysFailing,
                newlyBroken,
                stable,
                suiteHealthScore(analyses, flaky),
                flaky,
                analyses);
    }

    /** Aggregates every execution of every build under its test id, in build order. */
    Map<String, TestHistory> groupByTest(List<BuildInfo> builds) {
        Map<String, TestHistory> histories = new LinkedHashMap<>();
        for (BuildInfo build : builds) {
            for (TestExecution execution : build.executions()) {
                histories
                        .computeIfAbsent(execution.testId(),
                                id -> new TestHistory(execution.className(), execution.testName()))
                        .add(execution);
            }
        }
        histories.values().forEach(TestHistory::sortChronologically);
        return histories;
    }

    TestAnalysis analyzeTest(TestHistory history) {
        List<TestExecution> all = history.executions();
        List<TestExecution> conclusive = history.conclusiveExecutions();

        int passCount = (int) conclusive.stream().filter(e -> e.status().isPassed()).count();
        int failCount = (int) conclusive.stream().filter(e -> e.status().isFailed()).count();
        int skipCount = all.size() - conclusive.size();

        double passRate = conclusive.isEmpty() ? 0.0 : (double) passCount / conclusive.size();
        double failRate = conclusive.isEmpty() ? 0.0 : (double) failCount / conclusive.size();

        int flipCount = countFlips(conclusive);
        double flipRate = conclusive.size() < 2 ? 0.0 : (double) flipCount / (conclusive.size() - 1);
        int recentFlipCount = countFlips(lastN(conclusive, RECENT_WINDOW));
        int trailingFailures = countTrailingFailures(conclusive);

        double avgDuration = all.stream().mapToDouble(TestExecution::durationSeconds).average().orElse(0.0);
        double stdDev = stdDev(all, avgDuration);
        double cv = avgDuration > 0 ? stdDev / avgDuration : 0.0;

        Verdict verdict = decideVerdict(conclusive.size(), passCount, failCount, trailingFailures);

        double score = verdict == Verdict.FLAKY
                ? score(flipRate, passRate, failCount, cv)
                : 0.0;
        Severity severity = verdict == Verdict.FLAKY ? Severity.fromScore(score) : Severity.NONE;

        List<Integer> failedBuilds = conclusive.stream()
                .filter(e -> e.status().isFailed())
                .map(TestExecution::buildNumber)
                .toList();

        return new TestAnalysis(
                history.testId(),
                history.className(),
                history.testName(),
                verdict,
                severity,
                round(score),
                all.size(),
                passCount,
                failCount,
                skipCount,
                roundRate(passRate),
                roundRate(failRate),
                flipCount,
                roundRate(flipRate),
                recentFlipCount,
                trailingFailures,
                round(avgDuration),
                round(stdDev),
                round(cv),
                timeline(all),
                failedBuilds,
                indicators(verdict, flipCount, flipRate, recentFlipCount, passRate, failCount, cv, trailingFailures),
                sampleError(conclusive));
    }

    private Verdict decideVerdict(int conclusiveRuns, int passCount, int failCount, int trailingFailures) {
        if (conclusiveRuns < config.minRunsForVerdict()) {
            return Verdict.INSUFFICIENT_DATA;
        }
        if (failCount == 0) {
            return Verdict.STABLE;
        }
        if (passCount == 0) {
            return Verdict.ALWAYS_FAILING;
        }
        // Every failure sits in one unbroken block at the newest end: the test broke and stayed
        // broken. That is a regression to fix, not an intermittent test.
        if (trailingFailures == failCount && trailingFailures >= REGRESSION_STREAK) {
            return Verdict.NEWLY_BROKEN;
        }
        return Verdict.FLAKY;
    }

    private double score(double flipRate, double passRate, int failCount, double durationCv) {
        double flip = flipRate * WEIGHT_FLIP;
        double balance = (1.0 - Math.abs(passRate - 0.5) * 2.0) * WEIGHT_BALANCE;
        double volume = Math.min(failCount, 5) / 5.0 * WEIGHT_VOLUME;
        double duration = Math.min(durationCv, 1.0) * WEIGHT_DURATION;
        return Math.min(100.0, flip + balance + volume + duration);
    }

    private List<String> indicators(Verdict verdict, int flipCount, double flipRate, int recentFlips,
                                    double passRate, int failCount, double durationCv, int trailingFailures) {
        List<String> indicators = new ArrayList<>();

        switch (verdict) {
            case FLAKY -> {
                indicators.add(fmt("Alternates between PASS and FAIL: %d status flip(s) across the analysed builds (flip rate %.0f%%)", flipCount, flipRate * 100));
                if (recentFlips > 0) {
                    indicators.add(fmt("Still unstable: %d flip(s) within the last %d runs", recentFlips, RECENT_WINDOW));
                }
                if (passRate >= 0.35 && passRate <= 0.65) {
                    indicators.add(fmt("Near-even pass/fail split (%.0f%% pass) — outcome is close to a coin flip", passRate * 100));
                }
                if (failCount == 1) {
                    indicators.add("Only 1 failure so far — could still be a one-off; keep monitoring");
                }
                if (durationCv >= 0.5) {
                    indicators.add(fmt("Execution time is unstable (coefficient of variation %.2f) — suggests timing, waits or resource contention", durationCv));
                }
            }
            case NEWLY_BROKEN -> indicators.add(
                    fmt("Failed on the last %d consecutive runs after passing earlier — treat as a regression, not flakiness", trailingFailures));
            case ALWAYS_FAILING -> indicators.add("Failed on every analysed run — consistently broken");
            case INSUFFICIENT_DATA -> indicators.add(
                    fmt("Fewer than %d conclusive runs — not enough history for a verdict", config.minRunsForVerdict()));
            case STABLE -> indicators.add("Passed on every conclusive run");
        }
        return indicators;
    }

    /** Number of adjacent pass<->fail transitions; the primary evidence of intermittency. */
    static int countFlips(List<TestExecution> conclusive) {
        int flips = 0;
        for (int i = 1; i < conclusive.size(); i++) {
            if (conclusive.get(i).status() != conclusive.get(i - 1).status()) {
                flips++;
            }
        }
        return flips;
    }

    /** Length of the failure streak ending at the newest run (0 if the newest run passed). */
    static int countTrailingFailures(List<TestExecution> conclusive) {
        int streak = 0;
        for (int i = conclusive.size() - 1; i >= 0; i--) {
            if (!conclusive.get(i).status().isFailed()) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private static List<TestExecution> lastN(List<TestExecution> list, int n) {
        return list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }

    private static double stdDev(List<TestExecution> executions, double mean) {
        if (executions.size() < 2) {
            return 0.0;
        }
        double variance = executions.stream()
                .mapToDouble(e -> Math.pow(e.durationSeconds() - mean, 2))
                .sum() / executions.size();
        return Math.sqrt(variance);
    }

    /** Compact oldest-to-newest evidence string, e.g. "PPFPFP" — P pass, F fail, S skip. */
    private static String timeline(List<TestExecution> executions) {
        StringBuilder sb = new StringBuilder(executions.size());
        for (TestExecution execution : executions) {
            sb.append(switch (execution.status()) {
                case PASSED -> 'P';
                case FAILED -> 'F';
                case SKIPPED -> 'S';
            });
        }
        return sb.toString();
    }

    private static String sampleError(List<TestExecution> conclusive) {
        for (int i = conclusive.size() - 1; i >= 0; i--) {
            TestExecution execution = conclusive.get(i);
            if (execution.status().isFailed() && execution.errorDetails() != null) {
                return execution.errorDetails();
            }
        }
        return null;
    }

    /**
     * Suite health: share of tests that are not flaky, penalised by how severe the flakiness is.
     * 100 = nothing flaky.
     */
    private static double suiteHealthScore(List<TestAnalysis> all, List<TestAnalysis> flaky) {
        if (all.isEmpty()) {
            return 100.0;
        }
        double penalty = flaky.stream().mapToDouble(TestAnalysis::flakinessScore).sum();
        return round(Math.max(0.0, 100.0 - (penalty / all.size())));
    }

    private static int count(List<TestAnalysis> analyses, Verdict verdict) {
        return (int) analyses.stream().filter(a -> a.verdict() == verdict).count();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Rates stay fractions (0.0-1.0); 4 decimals keeps 1/3-style splits readable. */
    private static double roundRate(double rate) {
        return Math.round(rate * 10_000.0) / 10_000.0;
    }

    /** All numeric formatting is locale-independent, so the report is byte-identical on any agent. */
    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
