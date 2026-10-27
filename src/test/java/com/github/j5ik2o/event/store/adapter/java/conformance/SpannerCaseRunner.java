package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.spanner.ConformanceSpannerOperations;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerEmulator;

/** 実際の Spanner エミュレーターで操作を実行し、結果を共通データの期待値と比較する。 */
final class SpannerCaseRunner {
  private SpannerCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.file().startsWith("scenarios/core/")
        || c.file().startsWith("spanner/")
        || c.file().equals("scenarios/stores/shared.json")
        || c.operation().map("validateOccurredAt"::equals).orElse(false);
  }

  static CaseResult run(ConformanceCase c) {
    return ScenarioComparison.runBothPaths(
        c, Backend.SPANNER, asynchronous -> runPath(c, asynchronous));
  }

  private static CaseResult runPath(ConformanceCase c, boolean asynchronous) {
    SpannerEmulator emulator = SpannerEmulator.shared();
    return ScenarioComparison.run(
        c,
        Backend.SPANNER,
        null,
        new ScenarioComparison.Execution() {
          public ObjectNode scenario(JsonNode scenario) {
            return ConformanceSpannerOperations.executeScenario(scenario, emulator, asynchronous);
          }

          public ObjectNode occurredAt(JsonNode input, String caseId) {
            return ConformanceSpannerOperations.validateOccurredAt(
                input, caseId, emulator, asynchronous);
          }
        });
  }
}
