package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryCaseRunnerBoundaryTest {
  @TempDir Path directory;

  @Test
  void unexpectedRuntimeFailureDoesNotLoseOtherPathsOrLaterCaseReports() throws Exception {
    assertFailureIsReported(new IllegalStateException("unexpected path failure"));
  }

  @Test
  void unexpectedAssertionFailureDoesNotLoseOtherPathsOrLaterCaseReports() throws Exception {
    assertFailureIsReported(new AssertionError("unexpected assertion failure"));
  }

  @Test
  void actualMalformedTimeFailsBothPublicMemoryPathsAndKeepsLaterCase() throws Exception {
    ConformanceCase malformed = timeCase("local-malformed-time", "invalid-time");
    CaseResult failure = CaseClassifier.classify(malformed, Backend.MEMORY);
    assertEquals(ConformanceStatus.FAILED, failure.status());
    assertEquals("java.time.format.DateTimeParseException", failure.actual().at("/sync/exception").asText());
    assertEquals("java.time.format.DateTimeParseException", failure.actual().at("/async/exception").asText());
    assertSavedFailureAndLaterSuccess(failure);
  }

  private void assertFailureIsReported(Throwable original) throws Exception {
    for (boolean asynchronousFailure : List.of(false, true)) {
      ConformanceCase c = timeCase("local-unexpected-path-failure", "1970-01-01T00:00:00Z");
      List<Boolean> paths = new ArrayList<>();
      AtomicInteger unaffected = new AtomicInteger();
      CaseResult result = MemoryCaseRunner.run(c, asynchronous -> {
        paths.add(asynchronous);
        if (asynchronous == asynchronousFailure) {
          if (original instanceof RuntimeException) throw (RuntimeException) original;
          throw (AssertionError) original;
        }
        unaffected.incrementAndGet();
        return new CaseResult(c.id(), c.file(), c.rules(), Backend.MEMORY,
            ConformanceStatus.PASSED, null, null, c.materialized(),
            ConformanceJson.mapper().createObjectNode().put("value", "0"));
      });
      assertEquals(List.of(false, true), paths);
      assertEquals(1, unaffected.get());
      assertEquals(ConformanceStatus.FAILED, result.status());
      String failedPath = asynchronousFailure ? "async" : "sync";
      String successfulPath = asynchronousFailure ? "sync" : "async";
      assertEquals(original.getClass().getName(), result.actual().path(failedPath).path("exception").asText());
      assertEquals(original.getMessage(), result.actual().path(failedPath).path("message").asText());
      assertEquals("0", result.actual().path(successfulPath).path("value").asText());
      assertSavedFailureAndLaterSuccess(result);
    }
  }

  private void assertSavedFailureAndLaterSuccess(CaseResult failure) throws Exception {
    ConformanceCase later = timeCase("local-following-normal-case", "1970-01-01T00:00:00Z");
    CaseResult success = CaseClassifier.classify(later, Backend.MEMORY);
    assertEquals(ConformanceStatus.PASSED, success.status());
    assertEquals("0", success.actual().at("/sync/value").asText());
    assertEquals("0", success.actual().at("/async/value").asText());
    ConformanceReport report = new ConformanceReport("1.0.0",
        ManifestVerifier.verify(Path.of("conformance")), "test", null,
        List.of(failure, success), ConformanceJson.mapper().createArrayNode());
    report.write(directory);
    JsonNode saved = ConformanceJson.readTree(Files.readAllBytes(directory.resolve("report.json")), "report.json");
    assertEquals(2, saved.path("cases").size());
    assertEquals("failed", saved.at("/cases/0/status").asText());
    assertEquals(failure.actual(), saved.at("/cases/0/actual"));
    assertEquals("passed", saved.at("/cases/1/status").asText());
    assertEquals(later.id(), saved.at("/cases/1/case_id").asText());
    assertEquals("T-3", saved.at("/rules/0/rule").asText());
    assertEquals("failed", saved.at("/rules/0/status").asText());
    assertEquals("passed", saved.at("/rules/1/status").asText());
    assertTrue(Files.readString(directory.resolve("summary.md")).contains("| memory | 1 | 1 |"));
  }

  private static ConformanceCase timeCase(String id, String time) {
    ObjectNode raw = ConformanceJson.mapper().createObjectNode().put("operation", "validateOccurredAt");
    raw.putObject("input").put("event_seq_nr", 1).put("iso8601", time);
    raw.putObject("expect").put("value", "0");
    return new ConformanceCase(id, "values/local-time.json", "values", List.of("T-3"), raw, raw);
  }
}
