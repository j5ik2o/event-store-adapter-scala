package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableEmulator;
import com.github.j5ik2o.event.store.adapter.java.bigtable.ConformanceBigtableOperations;

/** 実際の Bigtable エミュレーターで操作を実行し、結果を共通データの期待値と比較する。 */
final class BigtableCaseRunner {
  private BigtableCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.file().startsWith("scenarios/core/")
        || c.file().startsWith("bigtable/")
        || c.file().equals("scenarios/stores/shared.json")
        || c.operation().map("validateOccurredAt"::equals).orElse(false);
  }

  static CaseResult run(ConformanceCase c) {
    return ScenarioComparison.runBothPaths(
        c, Backend.BIGTABLE, asynchronous -> runPath(c, asynchronous));
  }

  private static CaseResult runPath(ConformanceCase c, boolean asynchronous) {
    BigtableEmulator emulator = BigtableEmulator.shared();
    return ScenarioComparison.run(
        c,
        Backend.BIGTABLE,
        null,
        new ScenarioComparison.Execution() {
          public ObjectNode scenario(JsonNode scenario) {
            return ConformanceBigtableOperations.executeScenario(scenario, emulator, asynchronous);
          }

          public ObjectNode occurredAt(JsonNode input, String caseId) {
            return ConformanceBigtableOperations.validateOccurredAt(
                input, caseId, emulator, asynchronous);
          }
        });
  }
}
