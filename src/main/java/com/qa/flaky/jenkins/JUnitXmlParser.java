package com.qa.flaky.jenkins;

import com.qa.flaky.model.BuildInfo;
import com.qa.flaky.model.TestExecution;
import com.qa.flaky.model.TestStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Offline source of the same data as {@link JenkinsClient}: reads JUnit XML straight from
 * the workspace or from archived artifacts. Used when the REST API is unreachable, and by
 * the tests.
 *
 * <p>Expected layout — one directory per build, named with the build number:
 * <pre>
 *   results/
 *     101/ TEST-*.xml
 *     102/ TEST-*.xml
 * </pre>
 */
public class JUnitXmlParser {

    /** Reads every {@code <buildNumber>/**.xml} directory under {@code root}, oldest build first. */
    public List<BuildInfo> parseBuildHistory(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }

        List<BuildInfo> builds = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> buildDirs = dirs
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+"))
                    .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString())))
                    .toList();

            for (Path dir : buildDirs) {
                int buildNumber = Integer.parseInt(dir.getFileName().toString());
                Instant timestamp = Files.getLastModifiedTime(dir).toInstant();
                List<TestExecution> executions = parseDirectory(dir, buildNumber, timestamp);
                if (!executions.isEmpty()) {
                    builds.add(new BuildInfo(buildNumber, "UNKNOWN", timestamp, dir.toUri().toString(), executions));
                }
            }
        }
        return builds;
    }

    /** Parses every XML file below {@code dir} as the JUnit report of a single build. */
    public List<TestExecution> parseDirectory(Path dir, int buildNumber, Instant timestamp) throws IOException {
        List<TestExecution> executions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList()) {
                executions.addAll(parseFile(file, buildNumber, timestamp));
            }
        }
        return executions;
    }

    public List<TestExecution> parseFile(Path xmlFile, int buildNumber, Instant timestamp) throws IOException {
        try {
            Document doc = newSecureBuilder().parse(xmlFile.toFile());
            doc.getDocumentElement().normalize();

            List<TestExecution> executions = new ArrayList<>();
            NodeList testCases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testCases.getLength(); i++) {
                Node node = testCases.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element element = (Element) node;
                executions.add(new TestExecution(
                        attr(element, "classname", "UnknownClass"),
                        attr(element, "name", "unknownTest"),
                        statusOf(element),
                        parseDouble(attr(element, "time", "0")),
                        buildNumber,
                        timestamp,
                        errorOf(element)));
            }
            return executions;
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("Failed to parse JUnit XML: " + xmlFile, e);
        }
    }

    /** A testcase is failed if it carries <failure> or <error>, skipped if it carries <skipped>. */
    private static TestStatus statusOf(Element testCase) {
        if (testCase.getElementsByTagName("failure").getLength() > 0
                || testCase.getElementsByTagName("error").getLength() > 0) {
            return TestStatus.FAILED;
        }
        if (testCase.getElementsByTagName("skipped").getLength() > 0) {
            return TestStatus.SKIPPED;
        }
        return TestStatus.PASSED;
    }

    private static String errorOf(Element testCase) {
        for (String tag : new String[]{"failure", "error"}) {
            NodeList nodes = testCase.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                Element failure = (Element) nodes.item(0);
                String message = failure.getAttribute("message");
                if (message.isBlank()) {
                    message = failure.getTextContent();
                }
                String single = message.replaceAll("\\s+", " ").trim();
                if (single.isEmpty()) {
                    return null;
                }
                return single.length() > 300 ? single.substring(0, 297) + "..." : single;
            }
        }
        return null;
    }

    /** XXE-safe builder: this parses XML produced by CI, so external entities stay off. */
    private static DocumentBuilder newSecureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static String attr(Element element, String name, String fallback) {
        String value = element.getAttribute(name);
        return value.isBlank() ? fallback : value;
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
