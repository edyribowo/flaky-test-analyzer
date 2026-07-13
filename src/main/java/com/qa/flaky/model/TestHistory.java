package com.qa.flaky.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * All executions of a single test case across the analysed builds,
 * held in chronological order (oldest build first).
 */
public class TestHistory {

    private final String className;
    private final String testName;
    private final List<TestExecution> executions = new ArrayList<>();

    public TestHistory(String className, String testName) {
        this.className = className;
        this.testName = testName;
    }

    public void add(TestExecution execution) {
        executions.add(execution);
    }

    /** Must be called once all executions are collected; analysis assumes build order. */
    public void sortChronologically() {
        executions.sort(Comparator.comparingInt(TestExecution::buildNumber));
    }

    public String testId() {
        return className + "." + testName;
    }

    public String className() {
        return className;
    }

    public String testName() {
        return testName;
    }

    public List<TestExecution> executions() {
        return List.copyOf(executions);
    }

    /** Pass/fail sequence with skips removed — the input to every flakiness rule. */
    public List<TestExecution> conclusiveExecutions() {
        return executions.stream().filter(e -> e.status().isConclusive()).toList();
    }
}
