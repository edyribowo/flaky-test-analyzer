# Flaky Test Analyzer

Deterministic, rule-based flaky test detection for Jenkins regression suites.
You give it a **job name**; it pulls the build history and JUnit results from Jenkins itself
and produces a flaky test report. No uploads, no AI, no randomness — the same build history
always yields the same report.

```bash
mvn clean package
java -jar target/flaky-test-analyzer.jar nightly-regression --builds 50
```

## How it works

1. **Collect** — reads the last *N* builds of the job from the Jenkins REST API
   (`/job/<name>/api/json` for the build list, `/job/<name>/<n>/testReport/api/json` for the
   published JUnit results). Builds still running, or with no test report, are skipped.
2. **Aggregate** — groups every execution by test case (`className.testName`) across builds,
   in build order, producing a pass/fail timeline per test.
3. **Analyse** — applies the rules below. No AI is involved at this stage, so every verdict is
   explainable and reproducible.
4. **Report** — writes Markdown, HTML and JSON to the output directory.

## Detection rules

Applied to each test's pass/fail sequence, oldest build first (skipped runs are excluded —
they carry no signal):

| Condition | Verdict |
|---|---|
| Fewer than 3 conclusive runs | `INSUFFICIENT_DATA` |
| Never failed | `STABLE` |
| Never passed | `ALWAYS_FAILING` — consistently broken, **not** flaky |
| All failures form one unbroken streak (≥3) at the newest end | `NEWLY_BROKEN` — a regression, **not** flaky |
| Both passed and failed, alternating | `FLAKY` |

The `NEWLY_BROKEN` rule is what keeps a genuinely broken test out of the flaky list: a test
that passed until build #105 and has failed every build since is a regression to fix, not an
intermittent test.

### Flakiness score (0–100)

Only flaky tests are scored. Four weighted, independently explainable components:

| Component | Weight | Measures |
|---|---:|---|
| Flip rate | 45 | How often the outcome changes between adjacent builds |
| Pass/fail balance | 25 | Peaks at a 50/50 split — a coin-flip test |
| Failure volume | 15 | Repeated failures outrank a single one-off |
| Duration instability | 15 | Coefficient of variation of run time — points at timing, waits, contention |

Severity: **CRITICAL** ≥70 · **HIGH** ≥50 · **MEDIUM** ≥30 · else **LOW**.

## Output

Written to `flaky-report/` (override with `--output`):

| File | Purpose |
|---|---|
| `flaky-report.md` | Jenkins build summary, or a GitHub PR comment / issue body |
| `flaky-report.html` | Self-contained page archived as a Jenkins artifact (HTML Publisher plugin) |
| `flaky-report.json` | Machine-readable — **the input for the future AI module** |

Each flaky test reports its statistics (runs, pass/fail rates, flips), its timeline
(`PFPFPF`, oldest → newest), the builds it failed in, and the indicators that explain the
verdict. For root-causing, it also reports:

- **Common failure pattern** — the failure message that recurs most often (with a count),
  which is often more useful than whatever the single most recent failure happened to say.
- **Source** — the `File.java:line` inside the test's own class that the stack trace points
  at, extracted from the JUnit report's stack trace rather than the exception message alone.

Example console output:

```
Builds analysed : 8 (#101 - #108)
Tests observed  : 5
Flaky           : 3
Newly broken    : 1
Suite health    : 64.2/100

Flaky tests (worst first):
  [CRITICAL]  82.0  PFPFPFPF     com.shop.CheckoutTest.applyCoupon
  [HIGH    ]  59.2  PPPFPFPP     com.shop.CartTest.removeItem
  [MEDIUM  ]  37.8  PPPFPPPF     com.shop.LoginTest.loginWithOtp
```

## Configuration

Only the job name is required. Everything else has a default:

| Flag / variable | Default | Meaning |
|---|---|---|
| `<job-name>` (positional) | — | Jenkins job. Folder paths work: `team/regression` |
| `--builds <n>` | 20 | Recent builds to analyse. Pass `all` to analyse every build the job has (capped at 300) instead of picking a number per job |
| `--jenkins <url>` / `JENKINS_URL` | `http://localhost:8080` | Jenkins base URL — set automatically inside a Jenkins build |
| `JENKINS_USER` / `JENKINS_API_TOKEN` | — | Credentials for the REST API |
| `--output <dir>` | `flaky-report` | Where reports are written |
| `--from-xml <dir>` | — | Read JUnit XML from disk instead of the API; expects one directory per build (`<dir>/101/*.xml`) |

## Jenkins integration

See [`Jenkinsfile`](Jenkinsfile): it builds the jar, runs the analyzer against the regression
job, archives the reports, publishes the HTML, and marks the build **unstable** when flaky
tests are found. Detecting flakiness never *fails* the pipeline — whether to block on it is
the pipeline's decision, not the analyzer's.

## Project layout

```
com.qa.flaky
  FlakyTestAnalyzerApp      CLI entry point — job name in, reports out
  config/AnalyzerConfig     job name + Jenkins URL/credentials/limits
  jenkins/JenkinsClient     build history + JUnit results from the Jenkins REST API
  jenkins/JUnitXmlParser    same data from JUnit XML on disk (offline / workspace)
  model/                    TestExecution, TestHistory, TestAnalysis, AnalysisReport …
  analyzer/FlakyTestAnalyzer   the rule-based detection and scoring
  report/                   Markdown, HTML and JSON writers
```

## Next: the AI module

`flaky-report.json` is the hand-off. The deterministic layer establishes *which* tests are
flaky and *what* the evidence is; an LLM can then take that JSON and add root-cause analysis
and remediation suggestions on top — without re-deriving, or being able to alter, the detection.
