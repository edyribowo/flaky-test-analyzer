// Runs the analyzer after the scheduled regression job and publishes the report.
// The only input is the job name — build history and JUnit results are pulled from Jenkins.
pipeline {
    agent any

    parameters {
        string(name: 'TARGET_JOB', defaultValue: 'nightly-regression',
               description: 'Jenkins job whose build history should be analysed')
        string(name: 'BUILD_COUNT', defaultValue: 'all',
               description: "How many recent builds to analyse. Use a number, or 'all' to use " +
                             "every build the job has (capped at 300) — handy since jobs vary a lot " +
                             "in how much history they carry")
    }

    environment {
        // Jenkins' sh steps don't source the shell profile, so Homebrew's mvn
        // isn't on PATH by default on macOS agents.
        PATH   = "/opt/homebrew/bin:${env.PATH}"
        CLAUDE = '/Users/edy/.local/bin/claude'
    }

    triggers {
        // Run after the nightly regression suite has finished.
        cron('H 6 * * *')
    }

    stages {
        stage('Build analyzer') {
            steps {
                sh 'mvn -B -q clean package'
            }
        }

        stage('Analyze flaky tests') {
            steps {
                // JENKINS_URL is injected by Jenkins; the token comes from the credential store.
                withCredentials([usernamePassword(credentialsId: 'jenkins-api-token',
                                                  usernameVariable: 'JENKINS_USER',
                                                  passwordVariable: 'JENKINS_API_TOKEN')]) {
                    sh """
                        java -jar target/flaky-test-analyzer.jar '${params.TARGET_JOB}' \
                             --builds ${params.BUILD_COUNT} \
                             --output flaky-report
                    """
                }
            }
        }

        stage('AI flaky analysis') {
            steps {
                // No human is watching this job, so tool-use permission prompts (Read/Write)
                // would just hang the build forever -- skip them deliberately.
                sh '''
                    mkdir -p ai-report
                    set -o pipefail
                    ${CLAUDE} \
                        --dangerously-skip-permissions \
                        -p "/flaky-analysis-report flaky-report/flaky-report.md -- write the Markdown output to ai-report/flaky-analysis-report.md and the HTML output to ai-report/flaky-analysis-report.html" \
                        2>&1 | tee ai-flaky-analysis.log
                '''
            }
        }

        stage('Publish report') {
            steps {
                archiveArtifacts artifacts: 'flaky-report/*, ai-report/*, ai-flaky-analysis.log', fingerprint: true

                script {
                    def report = readJSON file: 'flaky-report/flaky-report.json'

                    // The AI report is the deliverable of this pipeline: fail loudly if it
                    // wasn't produced, rather than silently archiving nothing useful.
                    if (!fileExists('ai-report/flaky-analysis-report.md') || !fileExists('ai-report/flaky-analysis-report.html')) {
                        error("AI flaky analysis did not produce ai-report/flaky-analysis-report.md and .html")
                    }

                    currentBuild.description =
                        "${report.flakyCount} flaky · health ${report.suiteHealthScore}/100 · AI report generated"

                    // Flaky tests are explained in the AI report, not failed on: as long as
                    // that report was generated, the build is a success regardless of
                    // flakyCount. Whether to act on the findings is a human decision.
                }
            }
        }
    }
}
