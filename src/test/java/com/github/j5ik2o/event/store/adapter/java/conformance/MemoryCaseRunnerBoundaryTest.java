package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.memory.ConformanceMemoryOperations.MemoryStoreOpener;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
    ConformanceReport report = new ConformanceReport("1.2.0",
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

  @Test
  void memoryTtlCaseSucceedsWhenCreationFailsAsConfiguration() throws Exception {
    ConformanceCase ttl = dataCase("memory-ttl-unsupported");
    CaseResult result = CaseClassifier.classify(ttl, Backend.MEMORY);
    assertEquals(ConformanceStatus.PASSED, result.status(), String.valueOf(result.reason()));
    assertTrue(CaseClassifier.exclusion(ttl, Backend.MEMORY).isEmpty());
  }

  @Test
  void memoryTtlCaseFailsWhenTheExpectedCategoryIsNotConfiguration() throws Exception {
    ConformanceCase ttl = dataCase("memory-ttl-unsupported");
    ObjectNode altered = ttl.materialized().deepCopy();
    ((ObjectNode) altered.at("/initialization/expect/error")).put("category", "storage");
    ConformanceCase wrong =
        new ConformanceCase(ttl.id(), ttl.file(), ttl.format(), ttl.rules(), altered, altered);
    assertEquals(ConformanceStatus.FAILED, CaseClassifier.classify(wrong, Backend.MEMORY).status());
  }

  @Test
  void changeFeedCaseIsUnrepresentableWithAReasonAndNeverExecuted() throws Exception {
    ConformanceCase feed = dataCase("memory-change-feed-unsupported");
    CaseResult result = CaseClassifier.classify(feed, Backend.MEMORY);
    assertEquals(ConformanceStatus.UNREPRESENTABLE, result.status());
    assertNotNull(result.reason());
    assertFalse(result.reason().isBlank());
  }

  @Test
  void changeFeedRequestIsUnrepresentableForAnyCaseThatAsksForIt() {
    ConformanceCase requested = storeCase("local-change-feed", true);
    CaseResult result =
        CaseClassifier.exclusion(requested, Backend.MEMORY).orElseThrow(AssertionError::new);
    assertEquals(ConformanceStatus.UNREPRESENTABLE, result.status());
    assertFalse(result.reason().isBlank());
    assertTrue(CaseClassifier.exclusion(storeCase("local-no-change-feed", false), Backend.MEMORY).isEmpty());
    assertTrue(CaseClassifier.exclusion(storeCase("local-omitted", null), Backend.MEMORY).isEmpty());
  }

  @Test
  void anUnrepresentableResultStillViolatesARequirementThatListsTheCase() {
    CaseResult unrepresentable =
        CaseClassifier.exclusion(storeCase("local-change-feed", true), Backend.MEMORY)
            .orElseThrow(AssertionError::new);
    assertTrue(
        CaseClassifier.violatesRequirement(
            unrepresentable, java.util.Map.of(Backend.MEMORY, java.util.Set.of("local-change-feed"))));
    assertFalse(
        CaseClassifier.violatesRequirement(
            unrepresentable, java.util.Map.of(Backend.MEMORY, java.util.Set.<String>of())));
  }

  @Test
  void independentSecondStoreSceneSucceedsThroughThePublicApi() throws Exception {
    assertPassed(dataCase("memory-store-new-independent"));
  }

  @Test
  void sharedSecondStoreSceneSucceedsThroughThePublicApi() throws Exception {
    assertPassed(dataCase("memory-store-shared"));
  }

  @Test
  void writtenPayloadMutationSceneSucceedsThroughThePublicApi() throws Exception {
    assertPassed(dataCase("memory-payload-written-mutation"));
  }

  @Test
  void readPayloadMutationSceneSucceedsThroughThePublicApi() throws Exception {
    assertPassed(dataCase("memory-payload-read-mutation"));
  }

  @Test
  void newStorageThatSharesRecordsIsRejectedInTheIndependentScene() throws Exception {
    ConformanceCase scene = dataCase("memory-store-new-independent");
    AtomicReference<MemoryStorage> first = new AtomicReference<>();
    MemoryStoreOpener sharingEverything =
        (name, storage, config, asynchronous) -> {
          first.compareAndSet(null, storage);
          return MemoryStoreOpener.PUBLIC.open(name, first.get(), config, asynchronous);
        };
    CaseResult result = MemoryCaseRunner.run(scene, sharingEverything);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(3, result.failedOperation());
  }

  @Test
  void storeThatReturnsTheWrittenPayloadInstanceIsRejectedInTheWrittenMutationScene()
      throws Exception {
    CaseResult result =
        MemoryCaseRunner.run(
            dataCase("memory-payload-written-mutation"), (n, s, c, a) -> new AliasingStore());
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(3, result.failedOperation());
  }

  @Test
  void storeThatReturnsTheStoredPayloadInstanceIsRejectedInTheReadMutationScene() throws Exception {
    CaseResult result =
        MemoryCaseRunner.run(
            dataCase("memory-payload-read-mutation"), (n, s, c, a) -> new AliasingStore());
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(4, result.failedOperation());
  }

  @Test
  void unresolvablePointerFailsTheCaseInsteadOfSucceeding() throws Exception {
    ConformanceCase scene = dataCase("memory-payload-written-mutation");
    for (String pointer : List.of("", "/items/5/qty", "/items/x/qty", "/missing/qty")) {
      ObjectNode altered = scene.materialized().deepCopy();
      ((ObjectNode) altered.at("/steps/1/arguments")).put("pointer", pointer);
      ConformanceCase wrong =
          new ConformanceCase(scene.id(), scene.file(), scene.format(), scene.rules(), altered, altered);
      CaseResult result = MemoryCaseRunner.run(wrong);
      assertEquals(ConformanceStatus.FAILED, result.status(), pointer);
      assertEquals("java.lang.IllegalArgumentException",
          result.actual().at("/sync/exception").asText(), pointer);
      assertEquals("java.lang.IllegalArgumentException",
          result.actual().at("/async/exception").asText(), pointer);
    }
  }

  private static void assertPassed(ConformanceCase scene) {
    CaseResult result = CaseClassifier.classify(scene, Backend.MEMORY);
    assertEquals(ConformanceStatus.PASSED, result.status(), String.valueOf(result.reason()));
  }

  /** Keeps the caller's envelope as is and hands the same instance back, without copying. */
  private static final class AliasingStore implements EventStore<JsonNode, JsonNode> {
    private final List<EventEnvelope<JsonNode>> events = new ArrayList<>();

    @Override
    public void persistEvent(EventEnvelope<JsonNode> event) {
      events.add(event);
    }

    @Override
    public void persistEventAndSnapshot(
        EventEnvelope<JsonNode> event, SnapshotEnvelope<JsonNode> snapshot) {
      events.add(event);
    }

    @Override
    public Optional<SnapshotReadResult<JsonNode>> getLatestSnapshotById(AggregateId id) {
      return events.stream()
          .filter(e -> e.aggregateId().equals(id))
          .mapToLong(EventEnvelope::seqNr)
          .max()
          .stream()
          .mapToObj(SnapshotReadResult::<JsonNode>withoutSnapshot)
          .findFirst();
    }

    @Override
    public List<EventEnvelope<JsonNode>> getEventsByIdSinceSeqNr(AggregateId id, long seqNr) {
      List<EventEnvelope<JsonNode>> found = new ArrayList<>();
      for (EventEnvelope<JsonNode> e : events) {
        if (e.aggregateId().equals(id) && e.seqNr() >= seqNr) found.add(e);
      }
      return found;
    }
  }

  private static ConformanceCase dataCase(String id) throws Exception {
    return ConformanceDataLoader.load(Path.of("conformance")).cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing conformance case " + id));
  }

  private static ConformanceCase storeCase(String id, Boolean changeFeed) {
    ObjectNode raw = ConformanceJson.mapper().createObjectNode();
    ObjectNode store = raw.putObject("store");
    if (changeFeed != null) store.put("change_feed", changeFeed.booleanValue());
    return new ConformanceCase(id, "memory/local.json", "scenario", List.of("MEM-12"), raw, raw);
  }

  private static ConformanceCase timeCase(String id, String time) {
    ObjectNode raw = ConformanceJson.mapper().createObjectNode().put("operation", "validateOccurredAt");
    raw.putObject("input").put("event_seq_nr", 1).put("iso8601", time);
    raw.putObject("expect").put("value", "0");
    return new ConformanceCase(id, "values/local-time.json", "values", List.of("T-3"), raw, raw);
  }
}
