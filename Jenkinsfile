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
        PATH = "/opt/homebrew/bin:${env.PATH}"
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

        stage('Publish report') {
            steps {
                archiveArtifacts artifacts: 'flaky-report/*', fingerprint: true

                publishHTML(target: [
                    reportDir  : 'flaky-report',
                    reportFiles: 'flaky-report.html',
                    reportName : 'Flaky Test Report',
                    keepAll    : true,
                    alwaysLinkToLastBuild: true
                ])

                script {
                    def report = readJSON file: 'flaky-report/flaky-report.json'
                    currentBuild.description =
                        "${report.flakyCount} flaky · health ${report.suiteHealthScore}/100"

                    // Flakiness is reported, not failed on: mark the build unstable so it is
                    // visible without breaking the pipeline.
                    if (report.flakyCount > 0) {
                        unstable("Detected ${report.flakyCount} flaky test(s) in ${params.TARGET_JOB}")
                    }
                }
            }
        }
    }
}
