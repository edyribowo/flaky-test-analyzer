package com.qa.flaky.jenkins;

import com.qa.flaky.model.BuildInfo;
import com.qa.flaky.model.TestExecution;
import com.qa.flaky.model.TestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JUnitXmlParserTest {

    private final JUnitXmlParser parser = new JUnitXmlParser();

    @Test
    void parsesPassFailAndSkipFromJUnitXml(@TempDir Path dir) throws IOException {
        Path xml = dir.resolve("TEST-CheckoutTest.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="CheckoutTest" tests="3">
                  <testcase classname="com.shop.CheckoutTest" name="addToCart" time="1.25"/>
                  <testcase classname="com.shop.CheckoutTest" name="applyCoupon" time="2.50">
                    <failure message="expected 10 but was 0">at CheckoutTest.java:42</failure>
                  </testcase>
                  <testcase classname="com.shop.CheckoutTest" name="giftWrap" time="0">
                    <skipped/>
                  </testcase>
                </testsuite>
                """);

        List<TestExecution> executions = parser.parseFile(xml, 42, Instant.EPOCH);

        assertEquals(3, executions.size());
        assertEquals(TestStatus.PASSED, executions.get(0).status());
        assertEquals(1.25, executions.get(0).durationSeconds());
        assertEquals("com.shop.CheckoutTest.addToCart", executions.get(0).testId());

        assertEquals(TestStatus.FAILED, executions.get(1).status());
        assertEquals("expected 10 but was 0", executions.get(1).errorDetails());
        assertEquals(42, executions.get(1).buildNumber());

        assertEquals(TestStatus.SKIPPED, executions.get(2).status());
    }

    @Test
    void errorElementCountsAsFailure(@TempDir Path dir) throws IOException {
        Path xml = dir.resolve("TEST-ApiTest.xml");
        Files.writeString(xml, """
                <testsuite name="ApiTest">
                  <testcase classname="com.api.ApiTest" name="getUser" time="0.5">
                    <error message="Connection reset by peer"/>
                  </testcase>
                </testsuite>
                """);

        List<TestExecution> executions = parser.parseFile(xml, 7, Instant.EPOCH);

        assertEquals(TestStatus.FAILED, executions.get(0).status());
        assertEquals("Connection reset by peer", executions.get(0).errorDetails());
    }

    @Test
    void readsBuildHistoryFromNumberedDirectories(@TempDir Path root) throws IOException {
        writeBuild(root, 101, "PASSED");
        writeBuild(root, 102, "FAILED");

        List<BuildInfo> builds = parser.parseBuildHistory(root);

        assertEquals(2, builds.size());
        assertEquals(101, builds.get(0).number(), "builds are returned oldest first");
        assertEquals(102, builds.get(1).number());
        assertEquals(TestStatus.PASSED, builds.get(0).executions().get(0).status());
        assertEquals(TestStatus.FAILED, builds.get(1).executions().get(0).status());
        assertNotNull(builds.get(1).executions().get(0).errorDetails());
    }

    private void writeBuild(Path root, int buildNumber, String outcome) throws IOException {
        Path dir = Files.createDirectories(root.resolve(String.valueOf(buildNumber)));
        String body = "PASSED".equals(outcome)
                ? "<testcase classname=\"com.shop.LoginTest\" name=\"login\" time=\"1.0\"/>"
                : "<testcase classname=\"com.shop.LoginTest\" name=\"login\" time=\"1.0\">"
                  + "<failure message=\"timeout waiting for element\"/></testcase>";
        Files.writeString(dir.resolve("TEST-LoginTest.xml"),
                "<testsuite name=\"LoginTest\">" + body + "</testsuite>");
    }
}
