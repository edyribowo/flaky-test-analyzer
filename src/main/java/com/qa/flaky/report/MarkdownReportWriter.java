package com.qa.flaky.report;

import com.qa.flaky.model.AnalysisReport;
import com.qa.flaky.model.TestAnalysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Markdown report — the format posted to GitHub as a PR comment or issue body,
 * and rendered in the Jenkins build summary.
 */
public class MarkdownReportWriter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public Path write(AnalysisReport report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("flaky-report.md");
        Files.writeString(file, render(report), StandardCharsets.UTF_8);
        return file;
    }

    public String render(AnalysisReport report) {
        StringBuilder md = new StringBuilder();

        md.append("# 🔍 Flaky Test Report — `").append(report.jobName()).append("`\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| **Builds analysed** | ").append(report.buildsAnalyzed())
                .append(" (#").append(report.firstBuildNumber())
                .append(" → #").append(report.lastBuildNumber()).append(") |\n");
        md.append("| **Tests observed** | ").append(report.totalTests()).append(" |\n");
        md.append("| **Flaky** | ").append(report.flakyCount()).append(" |\n");
        md.append("| **Consistently failing** | ").append(report.alwaysFailingCount()).append(" |\n");
        md.append("| **Newly broken (regression)** | ").append(report.newlyBrokenCount()).append(" |\n");
        md.append("| **Stable** | ").append(report.stableCount()).append(" |\n");
        md.append("| **Suite health** | ").append(healthBadge(report.suiteHealthScore())).append(" |\n");
        md.append("| **Generated** | ").append(TIMESTAMP.format(report.generatedAt())).append(" |\n\n");

        if (report.flakyCount() == 0) {
            md.append("✅ **No flaky tests detected.** No test alternated between passing and failing across the analysed builds.\n");
            appendBrokenSection(md, report);
            appendMethodology(md);
            return md.toString();
        }

        md.append("## Flaky tests (worst first)\n\n");
        md.append("| Severity | Score | Test | Pass rate | Flips | Timeline (old → new) |\n");
        md.append("|---|---:|---|---:|---:|---|\n");
        for (TestAnalysis test : report.flakyBySeverity()) {
            md.append("| ").append(severityBadge(test)).append(" | ")
                    .append(fmt("%.0f", test.flakinessScore())).append(" | `")
                    .append(test.testName()).append("` | ")
                    .append(percent(test.passRate())).append(" | ")
                    .append(test.flipCount()).append(" | `")
                    .append(test.statusTimeline()).append("` |\n");
        }
        md.append("\n_Timeline: `P` pass, `F` fail, `S` skipped._\n\n");

        md.append("## Details\n\n");
        for (TestAnalysis test : report.flakyBySeverity()) {
            md.append("### ").append(severityBadge(test)).append(" `").append(test.testId()).append("`\n\n");
            md.append("- **Flakiness score:** ").append(fmt("%.0f/100", test.flakinessScore())).append('\n');
            md.append("- **Runs:** ").append(test.totalRuns())
                    .append(" (").append(test.passCount()).append(" passed, ")
                    .append(test.failCount()).append(" failed, ")
                    .append(test.skipCount()).append(" skipped)\n");
            md.append("- **Pass rate:** ").append(percent(test.passRate()))
                    .append(" · **Fail rate:** ").append(percent(test.failRate())).append('\n');
            md.append("- **Status flips:** ").append(test.flipCount())
                    .append(" (rate ").append(percent(test.flipRate())).append(")\n");
            md.append("- **Avg duration:** ").append(fmt("%.2fs", test.avgDurationSeconds()))
                    .append(" (± ").append(fmt("%.2fs", test.durationStdDevSeconds())).append(")\n");
            md.append("- **Failed in builds:** ").append(
                    test.failedBuilds().isEmpty() ? "—" : "#" + join(test.failedBuilds())).append('\n');
            md.append("- **Timeline:** `").append(test.statusTimeline()).append("`\n\n");

            md.append("**Why it is flagged**\n\n");
            for (String indicator : test.indicators()) {
                md.append("- ").append(indicator).append('\n');
            }
            if (test.commonError() != null && test.commonErrorCount() > 1) {
                md.append("\n**Common failure pattern** (").append(test.commonErrorCount())
                        .append("/").append(test.failCount()).append(" failures)\n\n```\n")
                        .append(test.commonError()).append("\n```\n");
            }
            if (test.sampleError() != null
                    && !test.sampleError().equals(test.commonError())) {
                md.append("\n**Most recent failure**\n\n```\n").append(test.sampleError()).append("\n```\n");
            }
            if (test.sampleErrorSource() != null) {
                md.append("\n**Source:** `").append(test.sampleErrorSource()).append("`\n");
            }
            md.append('\n');
        }

        appendBrokenSection(md, report);
        appendMethodology(md);
        return md.toString();
    }

    private void appendBrokenSection(StringBuilder md, AnalysisReport report) {
        var broken = report.allTests().stream()
                .filter(t -> t.verdict() == com.qa.flaky.model.Verdict.ALWAYS_FAILING
                        || t.verdict() == com.qa.flaky.model.Verdict.NEWLY_BROKEN)
                .toList();
        if (broken.isEmpty()) {
            return;
        }
        md.append("\n## Failing consistently (not flaky — fix the test or the code)\n\n");
        md.append("| Test | Verdict | Fail rate | Timeline |\n|---|---|---:|---|\n");
        for (TestAnalysis test : broken) {
            md.append("| `").append(test.testId()).append("` | ")
                    .append(test.verdict()).append(" | ")
                    .append(percent(test.failRate())).append(" | `")
                    .append(test.statusTimeline()).append("` |\n");
        }
        md.append('\n');
    }

    private void appendMethodology(StringBuilder md) {
        md.append("\n---\n\n<details><summary>How this is decided (rule-based, no AI)</summary>\n\n");
        md.append("A test is flagged **FLAKY** only when it both passed *and* failed across the analysed builds ")
                .append("without its failures forming one unbroken streak at the newest end — that streak pattern is ")
                .append("reported as a regression instead.\n\n");
        md.append("The 0-100 flakiness score weighs four measurements:\n\n");
        md.append("| Component | Weight | Meaning |\n|---|---:|---|\n");
        md.append("| Flip rate | 45 | How often the outcome changes between adjacent builds |\n");
        md.append("| Pass/fail balance | 25 | Peaks at a 50/50 split — a coin-flip test |\n");
        md.append("| Failure volume | 15 | Repeated failures outweigh a single one-off |\n");
        md.append("| Duration instability | 15 | Unstable runtimes point at timing/waits/contention |\n\n");
        md.append("Severity: **CRITICAL** ≥70, **HIGH** ≥50, **MEDIUM** ≥30, otherwise **LOW**. ");
        md.append("The same build history always produces the same report.\n\n</details>\n");
    }

    private static String severityBadge(TestAnalysis test) {
        return switch (test.severity()) {
            case CRITICAL -> "🔴 CRITICAL";
            case HIGH -> "🟠 HIGH";
            case MEDIUM -> "🟡 MEDIUM";
            case LOW -> "🔵 LOW";
            case NONE -> "—";
        };
    }

    private static String healthBadge(double score) {
        String icon = score >= 90 ? "🟢" : score >= 70 ? "🟡" : "🔴";
        return icon + fmt(" %.1f/100", score);
    }

    private static String percent(double rate) {
        return fmt("%.1f%%", rate * 100);
    }

    private static String join(java.util.List<Integer> builds) {
        return builds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", #"));
    }

    /** All numeric formatting is locale-independent, so the report is byte-identical on any agent. */
    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
