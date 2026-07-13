package com.qa.flaky.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qa.flaky.model.AnalysisReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Machine-readable report. This is the hand-off format for the future AI module:
 * an LLM gets the deterministic facts (timelines, rates, indicators) and adds
 * root-cause analysis on top, without re-deriving any of the detection.
 */
public class JsonReportWriter {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Path write(AnalysisReport report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("flaky-report.json");
        mapper.writeValue(file.toFile(), report);
        return file;
    }

    public String render(AnalysisReport report) throws IOException {
        return mapper.writeValueAsString(report);
    }
}
