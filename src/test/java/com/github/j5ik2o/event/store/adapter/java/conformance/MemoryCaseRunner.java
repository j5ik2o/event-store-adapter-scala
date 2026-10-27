package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.memory.ConformanceMemoryOperations;
import java.util.function.Function;

/** 実際のメモリ操作の結果を、共通データの期待値と比較する。 */
final class MemoryCaseRunner {
  private MemoryCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.file().startsWith("scenarios/core/")
        || c.file().startsWith("memory/")
        || c.operation().map("validateOccurredAt"::equals).orElse(false);
  }

  static CaseResult run(ConformanceCase c) {
    return run(c, ConformanceMemoryOperations.MemoryStoreOpener.PUBLIC);
  }

  static CaseResult run(
      ConformanceCase c, ConformanceMemoryOperations.MemoryStoreOpener opener) {
    return run(c, asynchronous -> runPath(c, asynchronous, opener));
  }

  static CaseResult run(ConformanceCase c, Function<Boolean, CaseResult> executePath) {
    return ScenarioComparison.runBothPaths(c, Backend.MEMORY, executePath);
  }

  private static CaseResult runPath(
      ConformanceCase c,
      boolean asynchronous,
      ConformanceMemoryOperations.MemoryStoreOpener opener) {
    return ScenarioComparison.run(
        c,
        Backend.MEMORY,
        null,
        new ScenarioComparison.Execution() {
          public ObjectNode scenario(JsonNode scenario) {
            return ConformanceMemoryOperations.executeScenario(scenario, asynchronous, opener);
          }

          public ObjectNode occurredAt(JsonNode input, String caseId) {
            return ConformanceMemoryOperations.validateOccurredAt(input, caseId, asynchronous);
          }
        });
  }
}
