package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opens a Bigtable storage whose retention deletion always fails, so that a test can prove the
 * retention-failure path was reached. The hook lives in the package-private extension point of the
 * Java storage, which is why this helper keeps the Java package name.
 */
public final class FailingRetentionStorage {
  private FailingRetentionStorage() {}

  /**
   * @param calls incremented once for every failed retention deletion
   */
  public static BigtableStorage open(
      BigtableEmulator emulator,
      BigtableEmulator.Tables tables,
      RetentionPolicy policy,
      AtomicInteger calls) {
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
            calls.incrementAndGet();
            throw new IllegalStateException("injected retention failure");
          }
        };
    return BigtableStorage.open(
        emulator.data(),
        emulator.admin(),
        BigtableStorageConfig.builder()
            .journalTableId(tables.journal)
            .headTableId(tables.head)
            .retentionPolicy(policy)
            .build(),
        hooks);
  }
}
