package com.qa.flaky.config;

import java.nio.file.Path;

/**
 * Everything the analyzer needs beyond the job name. Every field has a default or is
 * resolved from the environment, so the user only has to supply the job name.
 *
 * <p>Environment / system properties:
 * <ul>
 *   <li>{@code JENKINS_URL} — injected automatically by Jenkins inside a build</li>
 *   <li>{@code JENKINS_USER} + {@code JENKINS_API_TOKEN} — credentials for the REST API</li>
 * </ul>
 */
public record AnalyzerConfig(
        String jobName,
        String jenkinsUrl,
        String username,
        String apiToken,
        int buildLimit,
        int minRunsForVerdict,
        Path outputDir) {

    public static final int DEFAULT_BUILD_LIMIT = 20;
    public static final int DEFAULT_MIN_RUNS = 3;

    public static Builder builder(String jobName) {
        return new Builder(jobName);
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && apiToken != null && !apiToken.isBlank();
    }

    public static final class Builder {
        private final String jobName;
        private String jenkinsUrl = env("JENKINS_URL", "http://localhost:8080");
        private String username = env("JENKINS_USER", null);
        private String apiToken = env("JENKINS_API_TOKEN", null);
        private int buildLimit = DEFAULT_BUILD_LIMIT;
        private int minRunsForVerdict = DEFAULT_MIN_RUNS;
        private Path outputDir = Path.of("flaky-report");

        private Builder(String jobName) {
            if (jobName == null || jobName.isBlank()) {
                throw new IllegalArgumentException("Job name is required");
            }
            this.jobName = jobName.trim();
        }

        public Builder jenkinsUrl(String url) {
            if (url != null && !url.isBlank()) this.jenkinsUrl = url.trim();
            return this;
        }

        public Builder credentials(String username, String apiToken) {
            this.username = username;
            this.apiToken = apiToken;
            return this;
        }

        /** {@code limit == 0} means "all builds the job has", resolved by {@code JenkinsClient}. */
        public Builder buildLimit(int limit) {
            if (limit >= 0) this.buildLimit = limit;
            return this;
        }

        public Builder minRunsForVerdict(int minRuns) {
            if (minRuns > 0) this.minRunsForVerdict = minRuns;
            return this;
        }

        public Builder outputDir(Path dir) {
            if (dir != null) this.outputDir = dir;
            return this;
        }

        public AnalyzerConfig build() {
            String normalized = jenkinsUrl.endsWith("/")
                    ? jenkinsUrl.substring(0, jenkinsUrl.length() - 1)
                    : jenkinsUrl;
            return new AnalyzerConfig(jobName, normalized, username, apiToken,
                    buildLimit, minRunsForVerdict, outputDir);
        }

        private static String env(String key, String fallback) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                value = System.getProperty(key);
            }
            return (value == null || value.isBlank()) ? fallback : value;
        }
    }
}
