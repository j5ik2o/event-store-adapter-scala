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
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    ConformanceReport report = new ConformanceReport("1.3.0",
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

  @Test
  void sharedConfigurationSceneSucceedsThroughThePublicApi() throws Exception {
    assertPassed(dataCase("memory-store-shared-configuration"));
  }

  @Test
  void sharedStoreWithItsOwnDefaultConfigurationIsRejectedInTheSharedConfigurationScene()
      throws Exception {
    Map<MemoryStorage, SharedRecords> records = new IdentityHashMap<>();
    MemoryStoreOpener opener =
        (name, storage, config, asynchronous) ->
            new DefaultConfigurationStore(records.computeIfAbsent(storage, k -> new SharedRecords()));
    CaseResult result = MemoryCaseRunner.run(dataCase("memory-store-shared-configuration"), opener);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(2, result.failedOperation());
    assertTrue(result.reason().contains("履歴の active が違う"), result.reason());
    for (String path : List.of("sync", "async")) {
      assertEquals("snapshot", result.actual().at("/" + path + "/steps/4/result").asText(), path);
      assertEquals(3, result.actual().at("/" + path + "/steps/4/head_seq_nr").asInt(), path);
    }
  }

  @Test
  void eachProvidedBackendTargetsExactlyItsRequiredCasesAndNothingIsExcludedAsUnprovided()
      throws Exception {
    List<ConformanceCase> cases = ConformanceDataLoader.load(Path.of("conformance")).cases();
    Map<Backend, Integer> expected =
        Map.of(Backend.SQLITE, 67, Backend.SPANNER, 79, Backend.BIGTABLE, 87);
    Map<Backend, Set<String>> required = RequiredCases.load();
    for (Map.Entry<Backend, Integer> entry : expected.entrySet()) {
      Set<String> targets = new HashSet<>();
      for (ConformanceCase c : cases) {
        if (CaseClassifier.exclusion(c, entry.getKey()).isEmpty()) targets.add(c.id());
      }
      assertEquals(entry.getValue().intValue(), targets.size(), entry.getKey().reportName());
      assertEquals(required.get(entry.getKey()), targets, entry.getKey().reportName());
    }
    for (ConformanceCase c : cases) {
      for (Backend backend : Backend.values()) {
        CaseClassifier.exclusion(c, backend)
            .ifPresent(
                excluded ->
                    assertFalse(
                        excluded.reason().contains("未提供"), c.id() + " " + backend.reportName()));
      }
    }
  }

  @Test
  void backendSpecificCasesAreNotApplicableOnOtherBackendsOnlyBecauseOfTheirBackendsList()
      throws Exception {
    Map<String, Backend> owners =
        Map.of("sqlite/", Backend.SQLITE, "spanner/", Backend.SPANNER, "bigtable/", Backend.BIGTABLE);
    int checked = 0;
    for (ConformanceCase c : ConformanceDataLoader.load(Path.of("conformance")).cases()) {
      for (Map.Entry<String, Backend> owner : owners.entrySet()) {
        if (!c.file().startsWith(owner.getKey())) continue;
        for (Backend backend : Backend.values()) {
          if (backend == owner.getValue()) continue;
          CaseResult excluded =
              CaseClassifier.exclusion(c, backend).orElseThrow(() -> new AssertionError(c.id()));
          assertEquals(ConformanceStatus.NOT_APPLICABLE, excluded.status(), c.id());
          assertEquals(CaseClassifier.REASON_BACKEND, excluded.reason(), c.id());
          checked++;
        }
      }
    }
    assertTrue(checked > 0);
  }

  @Test
  void unknownOperationInAnApplicableMemoryCaseIsNotPassed() {
    ConformanceCase applicable = lockCase("local-hold-write-lock", "memory");
    assertEquals(ConformanceStatus.UNVERIFIED, CaseClassifier.classify(applicable, Backend.MEMORY).status());
    ConformanceCase otherBackend = lockCase("local-hold-write-lock-sqlite", "sqlite");
    CaseResult excluded =
        CaseClassifier.exclusion(otherBackend, Backend.MEMORY).orElseThrow(AssertionError::new);
    assertEquals(ConformanceStatus.NOT_APPLICABLE, excluded.status());
    assertEquals(CaseClassifier.REASON_BACKEND, excluded.reason());
  }

  @Test
  void unknownValueOperationWithoutBackendsIsNotPassed() {
    ObjectNode raw = ConformanceJson.mapper().createObjectNode().put("operation", "unknownOperation");
    raw.putObject("input");
    raw.putObject("expect");
    ConformanceCase unknown =
        new ConformanceCase("local-unknown-operation", "values/local-unknown.json", "values",
            List.of("T-3"), raw, raw);
    for (Backend backend : Backend.values()) {
      assertEquals(ConformanceStatus.UNVERIFIED, CaseClassifier.classify(unknown, backend).status());
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

  private static final class SharedRecords {
    final List<EventEnvelope<JsonNode>> events = new ArrayList<>();
    final Map<AggregateId, SnapshotEnvelope<JsonNode>> snapshots = new HashMap<>();
  }

  /**
   * 記録は共有するが、保存先の設定を使わず自分の既定の設定（保持なし）を使う偽の保存先。
   * 履歴は本物の保存先に書かないため、保持件数 1 の履歴が観測できない。
   */
  private static final class DefaultConfigurationStore implements EventStore<JsonNode, JsonNode> {
    private final SharedRecords records;

    DefaultConfigurationStore(SharedRecords records) {
      this.records = records;
    }

    @Override
    public void persistEvent(EventEnvelope<JsonNode> event) {
      records.events.add(event);
    }

    @Override
    public void persistEventAndSnapshot(
        EventEnvelope<JsonNode> event, SnapshotEnvelope<JsonNode> snapshot) {
      records.events.add(event);
      records.snapshots.put(event.aggregateId(), snapshot);
    }

    @Override
    public Optional<SnapshotReadResult<JsonNode>> getLatestSnapshotById(AggregateId id) {
      return records.events.stream()
          .filter(e -> e.aggregateId().equals(id))
          .mapToLong(EventEnvelope::seqNr)
          .max()
          .stream()
          .mapToObj(
              head -> {
                SnapshotEnvelope<JsonNode> snapshot = records.snapshots.get(id);
                return snapshot == null
                    ? SnapshotReadResult.<JsonNode>withoutSnapshot(head)
                    : SnapshotReadResult.of(snapshot, head);
              })
          .findFirst();
    }

    @Override
    public List<EventEnvelope<JsonNode>> getEventsByIdSinceSeqNr(AggregateId id, long seqNr) {
      List<EventEnvelope<JsonNode>> found = new ArrayList<>();
      for (EventEnvelope<JsonNode> e : records.events) {
        if (e.aggregateId().equals(id) && e.seqNr() >= seqNr) found.add(e);
      }
      return found;
    }
  }

  private static ConformanceCase lockCase(String id, String backend) {
    ObjectNode raw = ConformanceJson.mapper().createObjectNode();
    raw.putArray("backends").add(backend);
    raw.putObject("store");
    raw.putArray("steps").addObject().put("op", "holdWriteLock");
    return new ConformanceCase(id, "memory/local.json", "scenario", List.of("MEM-2"), raw, raw);
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
