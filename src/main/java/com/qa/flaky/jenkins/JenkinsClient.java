package com.qa.flaky.jenkins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.flaky.config.AnalyzerConfig;
import com.qa.flaky.model.BuildInfo;
import com.qa.flaky.model.TestExecution;
import com.qa.flaky.model.TestStatus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads build history and JUnit test results straight from the Jenkins REST API,
 * so the user never has to upload a report.
 *
 * <p>Endpoints used:
 * <pre>
 *   /job/{name}/api/json?tree=builds[number,result,timestamp,url]{0,N}
 *   /job/{name}/{buildNumber}/testReport/api/json    (published JUnit results)
 * </pre>
 * A build with no published JUnit report returns 404 on {@code testReport} and is skipped.
 */
public class JenkinsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ceiling applied when the caller asks for "all" builds (buildLimit <= 0), so a job with
     *  years of history doesn't turn one analysis into thousands of HTTP requests. */
    private static final int AUTO_BUILD_CAP = 300;

    private static final int MAX_STACK_TRACE_CHARS = 4000;

    private final AnalyzerConfig config;
    private final HttpClient http;

    public JenkinsClient(AnalyzerConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the most recent builds for the job, newest first, and attaches the JUnit
     * test executions of each. Builds still running, or without test results, are skipped.
     *
     * <p>{@code buildLimit() <= 0} means "as many as the job has" (up to {@link #AUTO_BUILD_CAP}):
     * jobs vary wildly in how much history they carry, so the caller doesn't have to know or
     * guess the right number up front.
     */
    public List<BuildInfo> fetchRecentBuilds() throws IOException, InterruptedException {
        int limit = config.buildLimit() > 0 ? config.buildLimit() : AUTO_BUILD_CAP;
        String tree = "builds[number,result,timestamp,url]{0," + limit + "}";
        String url = jobUrl() + "/api/json?tree=" + URLEncoder.encode(tree, StandardCharsets.UTF_8);

        JsonNode root = getJson(url)
                .orElseThrow(() -> new IOException("Job '" + config.jobName() + "' not found at " + config.jenkinsUrl()));

        List<BuildInfo> builds = new ArrayList<>();
        for (JsonNode buildNode : root.path("builds")) {
            int number = buildNode.path("number").asInt();
            String result = buildNode.path("result").asText(null);

            // result == null means the build is still in progress; its results are not final.
            if (result == null || "null".equals(result)) {
                continue;
            }

            List<TestExecution> executions = fetchTestReport(number, buildNode.path("timestamp").asLong());
            if (executions.isEmpty()) {
                continue;
            }

            builds.add(new BuildInfo(
                    number,
                    result,
                    Instant.ofEpochMilli(buildNode.path("timestamp").asLong()),
                    buildNode.path("url").asText(),
                    executions));
        }
        return builds;
    }

    /**
     * Reads the published JUnit results of one build. Handles both the flat
     * {@code suites[].cases[]} shape and the aggregated {@code childReports[]} shape
     * produced by matrix/pipeline jobs.
     */
    private List<TestExecution> fetchTestReport(int buildNumber, long timestampMillis)
            throws IOException, InterruptedException {

        String url = jobUrl() + "/" + buildNumber + "/testReport/api/json";
        var maybeRoot = getJson(url);
        if (maybeRoot.isEmpty()) {
            return List.of();
        }

        Instant timestamp = Instant.ofEpochMilli(timestampMillis);
        List<TestExecution> executions = new ArrayList<>();
        JsonNode root = maybeRoot.get();

        if (root.has("childReports")) {
            for (JsonNode child : root.path("childReports")) {
                collectSuites(child.path("result"), buildNumber, timestamp, executions);
            }
        } else {
            collectSuites(root, buildNumber, timestamp, executions);
        }
        return executions;
    }

    private void collectSuites(JsonNode resultNode, int buildNumber, Instant timestamp,
                               List<TestExecution> sink) {
        for (JsonNode suite : resultNode.path("suites")) {
            for (JsonNode testCase : suite.path("cases")) {
                sink.add(new TestExecution(
                        testCase.path("className").asText("UnknownClass"),
                        testCase.path("name").asText("unknownTest"),
                        TestStatus.fromJenkins(testCase.path("status").asText(null)),
                        testCase.path("duration").asDouble(0.0),
                        buildNumber,
                        timestamp,
                        trimError(testCase.path("errorDetails").asText(null)),
                        trimStackTrace(testCase.path("errorStackTrace").asText(null))));
            }
        }
    }

    private java.util.Optional<JsonNode> getJson(String url) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET();

        if (config.hasCredentials()) {
            String basic = Base64.getEncoder().encodeToString(
                    (config.username() + ":" + config.apiToken()).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + basic);
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 404) {
            return java.util.Optional.empty();   // no test report published for this build
        }
        if (status == 401 || status == 403) {
            throw new IOException("Jenkins rejected the request (HTTP " + status
                    + "). Set JENKINS_USER and JENKINS_API_TOKEN.");
        }
        if (status >= 400) {
            throw new IOException("Jenkins returned HTTP " + status + " for " + url);
        }
        return java.util.Optional.of(MAPPER.readTree(response.body()));
    }

    /** Supports folders and multibranch jobs: "team/regression" -> /job/team/job/regression. */
    private String jobUrl() {
        StringBuilder sb = new StringBuilder(config.jenkinsUrl());
        for (String segment : config.jobName().split("/")) {
            if (!segment.isBlank()) {
                sb.append("/job/").append(encodePathSegment(segment));
            }
        }
        return sb.toString();
    }

    /**
     * URLEncoder implements application/x-www-form-urlencoded, which encodes spaces as
     * '+' — valid in a query string but not a URL path segment, where only '%20' is
     * recognised. Jenkins job names commonly contain spaces, so this matters.
     */
    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String single = error.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 297) + "..." : single;
    }

    /** Unlike {@link #trimError}, newlines are kept — the source-frame extraction needs them. */
    private static String trimStackTrace(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return null;
        }
        String trimmed = stackTrace.strip();
        return trimmed.length() > MAX_STACK_TRACE_CHARS
                ? trimmed.substring(0, MAX_STACK_TRACE_CHARS) + "\n... (truncated)"
                : trimmed;
    }
}
