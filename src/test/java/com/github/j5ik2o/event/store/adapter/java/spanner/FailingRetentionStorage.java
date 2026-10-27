package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opens a Spanner storage whose retention deletion always fails, so that a test can prove the
 * retention-failure path was reached. The hook lives in the package-private extension point of the
 * Java storage, which is why this helper keeps the Java package name.
 */
public final class FailingRetentionStorage {
  private FailingRetentionStorage() {}

  /**
   * @param calls incremented once for every failed retention deletion
   */
  public static SpannerStorage open(
      SpannerEmulator emulator,
      String prefix,
      SpannerRetentionPolicy policy,
      AtomicInteger calls) {
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
            calls.incrementAndGet();
            throw new IllegalStateException("injected retention failure");
          }
        };
    return SpannerStorage.open(
        emulator.client(),
        SpannerStorageConfig.builder().tablePrefix(prefix).retentionPolicy(policy).build(),
        hooks);
  }
}
