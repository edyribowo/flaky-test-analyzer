package com.qa.flaky;

import com.qa.flaky.analyzer.FlakyTestAnalyzer;
import com.qa.flaky.config.AnalyzerConfig;
import com.qa.flaky.jenkins.JUnitXmlParser;
import com.qa.flaky.jenkins.JenkinsClient;
import com.qa.flaky.model.AnalysisReport;
import com.qa.flaky.model.BuildInfo;
import com.qa.flaky.model.TestAnalysis;
import com.qa.flaky.report.HtmlReportWriter;
import com.qa.flaky.report.JsonReportWriter;
import com.qa.flaky.report.MarkdownReportWriter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.List;

/**
 * Entry point. The only required input is the Jenkins job name — everything else
 * (Jenkins URL, credentials) is read from the environment, which Jenkins populates itself.
 *
 * <pre>
 *   java -jar flaky-test-analyzer.jar nightly-regression
 *   java -jar flaky-test-analyzer.jar nightly-regression --builds 50
 *   java -jar flaky-test-analyzer.jar nightly-regression --from-xml results/
 * </pre>
 *
 * <p>Exit codes: 0 = analysis completed, 1 = usage/connection error.
 * A non-zero exit is never used to signal "flaky tests found" — the report says that,
 * and failing the build on flakiness is the pipeline's decision, not the analyzer's.
 */
public class FlakyTestAnalyzerApp {

    public static void main(String[] args) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
        }

        try {
            Options options = Options.parse(args);
            AnalyzerConfig config = AnalyzerConfig.builder(options.jobName)
                    .jenkinsUrl(options.jenkinsUrl)
                    .buildLimit(options.buildLimit)
                    .outputDir(Path.of(options.outputDir))
                    .build();

            System.out.printf(Locale.ROOT, "Analyzing job '%s' (last %d builds)%n", config.jobName(), config.buildLimit());

            List<BuildInfo> builds = options.xmlDir != null
                    ? new JUnitXmlParser().parseBuildHistory(Path.of(options.xmlDir))
                    : new JenkinsClient(config).fetchRecentBuilds();

            if (builds.isEmpty()) {
                System.err.println("No completed builds with JUnit test results were found for '"
                        + config.jobName() + "'. Nothing to analyze.");
                System.exit(1);
            }

            AnalysisReport report = new FlakyTestAnalyzer(config).analyze(builds, Instant.now());

            Path md = new MarkdownReportWriter().write(report, config.outputDir());
            Path html = new HtmlReportWriter().write(report, config.outputDir());
            Path json = new JsonReportWriter().write(report, config.outputDir());

            printSummary(report);
            System.out.println("\nReports written:");
            System.out.println("  " + md.toAbsolutePath());
            System.out.println("  " + html.toAbsolutePath());
            System.out.println("  " + json.toAbsolutePath());

        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Analysis failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printSummary(AnalysisReport report) {
        System.out.printf(Locale.ROOT, "%nBuilds analysed : %d (#%d - #%d)%n",
                report.buildsAnalyzed(), report.firstBuildNumber(), report.lastBuildNumber());
        System.out.printf(Locale.ROOT, "Tests observed  : %d%n", report.totalTests());
        System.out.printf(Locale.ROOT, "Flaky           : %d%n", report.flakyCount());
        System.out.printf(Locale.ROOT, "Always failing  : %d%n", report.alwaysFailingCount());
        System.out.printf(Locale.ROOT, "Newly broken    : %d%n", report.newlyBrokenCount());
        System.out.printf(Locale.ROOT, "Suite health    : %.1f/100%n", report.suiteHealthScore());

        if (report.flakyCount() > 0) {
            System.out.println("\nFlaky tests (worst first):");
            for (TestAnalysis test : report.flakyBySeverity()) {
                System.out.printf(Locale.ROOT, "  [%-8s] %5.1f  %-12s %s%n",
                        test.severity(), test.flakinessScore(), test.statusTimeline(), test.testId());
            }
        }
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg);
    }

    private static void printUsage() {
        System.out.println("""

                Flaky Test Analyzer — deterministic, rule-based flaky test detection for Jenkins

                Usage:
                  flaky-test-analyzer <job-name> [options]

                Options:
                  --builds <n>        Number of recent builds to analyze (default: 20)
                  --jenkins <url>     Jenkins base URL (default: $JENKINS_URL, else http://localhost:8080)
                  --output <dir>      Output directory for reports (default: flaky-report)
                  --from-xml <dir>    Read JUnit XML from disk instead of the Jenkins API.
                                      Expects one sub-directory per build: <dir>/101/*.xml, <dir>/102/*.xml
                  -h, --help          Show this help

                Environment:
                  JENKINS_URL         Jenkins base URL (set automatically inside a Jenkins build)
                  JENKINS_USER        Jenkins username for the REST API
                  JENKINS_API_TOKEN   Jenkins API token

                Example:
                  export JENKINS_URL=https://ci.example.com
                  export JENKINS_USER=qa-bot
                  export JENKINS_API_TOKEN=****
                  flaky-test-analyzer nightly-regression --builds 50
                """);
    }

    /** Minimal argument parsing — the job name is positional, everything else is optional. */
    private record Options(String jobName, String jenkinsUrl, int buildLimit, String outputDir, String xmlDir) {

        static Options parse(String[] args) {
            String jobName = args[0];
            String jenkinsUrl = null;
            int buildLimit = AnalyzerConfig.DEFAULT_BUILD_LIMIT;
            String outputDir = "flaky-report";
            String xmlDir = null;

            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--builds" -> buildLimit = Integer.parseInt(requireValue(args, ++i, "--builds"));
                    case "--jenkins" -> jenkinsUrl = requireValue(args, ++i, "--jenkins");
                    case "--output" -> outputDir = requireValue(args, ++i, "--output");
                    case "--from-xml" -> xmlDir = requireValue(args, ++i, "--from-xml");
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new Options(jobName, jenkinsUrl, buildLimit, outputDir, xmlDir);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
