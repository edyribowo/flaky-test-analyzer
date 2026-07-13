package com.qa.flaky.report;

import com.qa.flaky.model.AnalysisReport;
import com.qa.flaky.model.Severity;
import com.qa.flaky.model.TestAnalysis;
import com.qa.flaky.model.Verdict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Self-contained HTML report, archived as a Jenkins artifact and viewable in the
 * HTML Publisher plugin. No external CSS/JS, so it renders under Jenkins' strict CSP.
 *
 * <p>Visual encoding: status colours (pass/fail/skip) are paired with a letter in every
 * timeline cell and severity is shown as icon + word, so no meaning is carried by colour alone.
 */
public class HtmlReportWriter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public Path write(AnalysisReport report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("flaky-report.html");
        Files.writeString(file, render(report), StandardCharsets.UTF_8);
        return file;
    }

    public String render(AnalysisReport report) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Flaky Test Report — ").append(escape(report.jobName())).append("</title>\n")
                .append("<style>\n").append(css()).append("</style>\n</head>\n<body>\n<main class=\"viz-root\">\n");

        html.append("<header>\n<h1>Flaky Test Report</h1>\n")
                .append("<p class=\"sub\"><code>").append(escape(report.jobName())).append("</code> · builds #")
                .append(report.firstBuildNumber()).append("–#").append(report.lastBuildNumber())
                .append(" · generated ").append(TIMESTAMP.format(report.generatedAt()))
                .append("</p>\n</header>\n");

        appendKpis(html, report);
        appendFlakySection(html, report);
        appendBrokenSection(html, report);
        appendMethodology(html);

        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    /** KPI row — plain stat tiles: single headline numbers, so no plot and no tooltip layer. */
    private void appendKpis(StringBuilder html, AnalysisReport report) {
        html.append("<section class=\"kpis\">\n");
        tile(html, "Suite health", fmt("%.1f", report.suiteHealthScore()), "/ 100",
                report.suiteHealthScore() >= 90 ? "good" : report.suiteHealthScore() >= 70 ? "warning" : "critical");
        tile(html, "Flaky tests", String.valueOf(report.flakyCount()), "intermittent",
                report.flakyCount() == 0 ? "good" : "serious");
        tile(html, "Consistently failing", String.valueOf(report.alwaysFailingCount() + report.newlyBrokenCount()),
                "broken, not flaky",
                report.alwaysFailingCount() + report.newlyBrokenCount() == 0 ? "good" : "critical");
        tile(html, "Tests observed", String.valueOf(report.totalTests()),
                report.buildsAnalyzed() + " builds analysed", "neutral");
        html.append("</section>\n");
    }

    private void tile(StringBuilder html, String label, String value, String caption, String status) {
        html.append("<div class=\"tile\">\n")
                .append("<div class=\"tile-label\">").append(escape(label)).append("</div>\n")
                .append("<div class=\"tile-value status-").append(status).append("\">").append(escape(value)).append("</div>\n")
                .append("<div class=\"tile-caption\">").append(escape(caption)).append("</div>\n")
                .append("</div>\n");
    }

    private void appendFlakySection(StringBuilder html, AnalysisReport report) {
        html.append("<section>\n<h2>Flaky tests</h2>\n");

        if (report.flakyCount() == 0) {
            html.append("<p class=\"empty\">No flaky tests detected — no test alternated between passing and failing across the analysed builds.</p>\n</section>\n");
            return;
        }

        html.append("<p class=\"sub\">Worst first. Timeline runs oldest build → newest: ")
                .append("<span class=\"cell pass\">P</span> passed, ")
                .append("<span class=\"cell fail\">F</span> failed, ")
                .append("<span class=\"cell skip\">S</span> skipped.</p>\n");

        html.append("<table>\n<thead><tr>")
                .append("<th>Severity</th><th class=\"num\">Score</th><th>Test</th>")
                .append("<th class=\"num\">Pass rate</th><th class=\"num\">Flips</th>")
                .append("<th class=\"num\">Avg time</th><th>Timeline (old → new)</th>")
                .append("</tr></thead>\n<tbody>\n");

        for (TestAnalysis test : report.flakyBySeverity()) {
            html.append("<tr>")
                    .append("<td>").append(severityBadge(test.severity())).append("</td>")
                    .append("<td class=\"num\">").append(fmt("%.0f", test.flakinessScore())).append("</td>")
                    .append("<td><code title=\"").append(escape(test.testId())).append("\">")
                    .append(escape(test.testName())).append("</code><div class=\"muted\">")
                    .append(escape(test.className())).append("</div></td>")
                    .append("<td class=\"num\">").append(percent(test.passRate())).append("</td>")
                    .append("<td class=\"num\">").append(test.flipCount()).append("</td>")
                    .append("<td class=\"num\">").append(fmt("%.2fs", test.avgDurationSeconds())).append("</td>")
                    .append("<td>").append(timelineStrip(test)).append("</td>")
                    .append("</tr>\n");

            html.append("<tr class=\"detail\"><td></td><td colspan=\"6\">\n<ul>\n");
            for (String indicator : test.indicators()) {
                html.append("<li>").append(escape(indicator)).append("</li>\n");
            }
            if (!test.failedBuilds().isEmpty()) {
                html.append("<li>Failed in builds: ").append(buildLinks(test)).append("</li>\n");
            }
            html.append("</ul>\n");
            if (test.commonError() != null && test.commonErrorCount() > 1) {
                html.append("<p class=\"muted\">Common failure pattern (")
                        .append(test.commonErrorCount()).append("/").append(test.failCount())
                        .append(" failures)</p>\n<pre>").append(escape(test.commonError())).append("</pre>\n");
            }
            if (test.sampleErrorStackTrace() != null) {
                html.append("<p class=\"muted\">Most recent failure");
                if (test.sampleErrorSource() != null) {
                    html.append(" — <code>").append(escape(test.sampleErrorSource())).append("</code>");
                }
                html.append("</p>\n<pre>").append(escape(test.sampleErrorStackTrace())).append("</pre>\n");
            } else if (test.sampleError() != null && !test.sampleError().equals(test.commonError())) {
                html.append("<p class=\"muted\">Most recent failure</p>\n<pre>")
                        .append(escape(test.sampleError())).append("</pre>\n");
            }
            html.append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n</section>\n");
    }

    private void appendBrokenSection(StringBuilder html, AnalysisReport report) {
        List<TestAnalysis> broken = report.allTests().stream()
                .filter(t -> t.verdict() == Verdict.ALWAYS_FAILING || t.verdict() == Verdict.NEWLY_BROKEN)
                .toList();
        if (broken.isEmpty()) {
            return;
        }

        html.append("<section>\n<h2>Failing consistently</h2>\n")
                .append("<p class=\"sub\">Deterministic failures — a real defect or a broken test, not flakiness.</p>\n")
                .append("<table>\n<thead><tr><th>Test</th><th>Verdict</th><th class=\"num\">Fail rate</th><th>Timeline</th></tr></thead>\n<tbody>\n");
        for (TestAnalysis test : broken) {
            html.append("<tr>")
                    .append("<td><code>").append(escape(test.testId())).append("</code></td>")
                    .append("<td>").append(test.verdict()).append("</td>")
                    .append("<td class=\"num\">").append(percent(test.failRate())).append("</td>")
                    .append("<td>").append(timelineStrip(test)).append("</td>")
                    .append("</tr>\n");
        }
        html.append("</tbody>\n</table>\n</section>\n");
    }

    private void appendMethodology(StringBuilder html) {
        html.append("<section>\n<details>\n<summary>How this is decided (rule-based, no AI)</summary>\n")
                .append("<p>A test is flagged <strong>flaky</strong> only when it both passed and failed across the analysed builds, ")
                .append("and its failures do <em>not</em> form one unbroken streak at the newest end — that pattern is reported as a regression instead. ")
                .append("The score weighs flip rate (45), pass/fail balance (25), failure volume (15) and duration instability (15). ")
                .append("Severity: CRITICAL ≥70, HIGH ≥50, MEDIUM ≥30, else LOW. The same build history always produces the same report.</p>\n")
                .append("</details>\n</section>\n");
    }

    /** Each cell carries its letter and a tooltip, so status is never colour-alone. */
    private String timelineStrip(TestAnalysis test) {
        StringBuilder strip = new StringBuilder("<div class=\"timeline\">");
        String timeline = test.statusTimeline();
        for (int i = 0; i < timeline.length(); i++) {
            char c = timeline.charAt(i);
            String cls = switch (c) {
                case 'P' -> "pass";
                case 'F' -> "fail";
                default -> "skip";
            };
            String label = switch (c) {
                case 'P' -> "passed";
                case 'F' -> "failed";
                default -> "skipped";
            };
            strip.append("<span class=\"cell ").append(cls).append("\" title=\"run ")
                    .append(i + 1).append(" of ").append(timeline.length()).append(": ")
                    .append(label).append("\">").append(c).append("</span>");
        }
        strip.append("</div>");
        return strip.toString();
    }

    private String buildLinks(TestAnalysis test) {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < test.failedBuilds().size(); i++) {
            if (i > 0) {
                links.append(", ");
            }
            links.append("#").append(test.failedBuilds().get(i));
        }
        return links.toString();
    }

    private static String severityBadge(Severity severity) {
        String status = switch (severity) {
            case CRITICAL -> "critical";
            case HIGH -> "serious";
            case MEDIUM -> "warning";
            case LOW -> "neutral";
            case NONE -> "neutral";
        };
        String icon = switch (severity) {
            case CRITICAL -> "●";   // filled circle
            case HIGH -> "◐";       // half circle
            case MEDIUM -> "○";     // open circle
            default -> "–";
        };
        return "<span class=\"badge status-" + status + "\">" + icon + " " + severity + "</span>";
    }

    private static String percent(double rate) {
        return fmt("%.0f%%", rate * 100);
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String css() {
        return """
                :root {
                  --surface-1: #fcfcfb;
                  --plane: #f9f9f7;
                  --text-primary: #0b0b0b;
                  --text-secondary: #52514e;
                  --text-muted: #898781;
                  --grid: #e1e0d9;
                  --border: rgba(11,11,11,0.10);
                  --good: #0ca30c;
                  --warning: #fab219;
                  --serious: #ec835a;
                  --critical: #d03b3b;
                }
                @media (prefers-color-scheme: dark) {
                  :root {
                    --surface-1: #1a1a19;
                    --plane: #0d0d0d;
                    --text-primary: #ffffff;
                    --text-secondary: #c3c2b7;
                    --text-muted: #898781;
                    --grid: #2c2c2a;
                    --border: rgba(255,255,255,0.10);
                  }
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
                  background: var(--plane);
                  color: var(--text-primary);
                }
                .viz-root { max-width: 1100px; margin: 0 auto; padding: 32px 20px 64px; }
                h1 { font-size: 24px; margin: 0 0 4px; }
                h2 { font-size: 17px; margin: 36px 0 10px; }
                .sub { color: var(--text-secondary); font-size: 13px; margin: 0 0 12px; }
                .muted { color: var(--text-muted); font-size: 11px; }
                code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }

                .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-top: 20px; }
                .tile { background: var(--surface-1); border: 1px solid var(--border); border-radius: 8px; padding: 14px 16px; }
                .tile-label { font-size: 12px; color: var(--text-secondary); }
                .tile-value { font-size: 30px; font-weight: 600; line-height: 1.2; margin: 2px 0; }
                .tile-caption { font-size: 11px; color: var(--text-muted); }

                .status-good { color: var(--good); }
                .status-warning { color: var(--warning); }
                .status-serious { color: var(--serious); }
                .status-critical { color: var(--critical); }
                .status-neutral { color: var(--text-primary); }

                .badge { font-size: 11px; font-weight: 600; white-space: nowrap; }

                table { width: 100%; border-collapse: collapse; background: var(--surface-1);
                        border: 1px solid var(--border); border-radius: 8px; overflow: hidden; font-size: 13px; }
                thead th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: .04em;
                           color: var(--text-muted); font-weight: 600; padding: 10px 12px; border-bottom: 1px solid var(--grid); }
                td { padding: 10px 12px; border-bottom: 1px solid var(--grid); vertical-align: top; }
                .num { text-align: right; font-variant-numeric: tabular-nums; }
                tr.detail td { padding-top: 0; border-bottom: 1px solid var(--grid); }
                tr.detail ul { margin: 0 0 6px; padding-left: 18px; color: var(--text-secondary); font-size: 12px; }
                tr.detail li { margin: 2px 0; }
                pre { background: var(--plane); border: 1px solid var(--border); border-radius: 6px;
                      padding: 8px 10px; font-size: 11px; overflow-x: auto; color: var(--text-secondary); margin: 0 0 6px; }

                .timeline { display: flex; flex-wrap: wrap; gap: 2px; }
                .cell { display: inline-flex; align-items: center; justify-content: center;
                        width: 18px; height: 18px; border-radius: 4px; font-size: 10px; font-weight: 700; color: #fff; }
                .cell.pass { background: var(--good); }
                .cell.fail { background: var(--critical); }
                .cell.skip { background: var(--text-muted); }

                .empty { background: var(--surface-1); border: 1px solid var(--border); border-radius: 8px;
                         padding: 16px; color: var(--text-secondary); font-size: 13px; }
                details { background: var(--surface-1); border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; }
                summary { cursor: pointer; font-size: 13px; font-weight: 600; }
                details p { color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
                """;
    }

    /** All numeric formatting is locale-independent, so the report is byte-identical on any agent. */
    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
