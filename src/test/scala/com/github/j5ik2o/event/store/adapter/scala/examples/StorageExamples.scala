package com.github.j5ik2o.event.store.adapter.scala.examples

import com.github.j5ik2o.event.store.adapter.java.bigtable.{BigtableStorage, BigtableStorageConfig}
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.spanner.{SpannerRetentionPolicy, SpannerStorage, SpannerStorageConfig}
import com.github.j5ik2o.event.store.adapter.java.sqlite.{SqliteStorage, SqliteStorageConfig}
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient
import com.google.cloud.bigtable.data.v2.BigtableDataClient
import com.google.cloud.spanner.DatabaseClient

import java.nio.file.Path
import java.time.Duration

/**
 * The storage set-up code shown in the README. The README Storages examples are this code, and AccountExampleSpec runs
 * both the first launch and the restart. / READMEの保存先の例と同じ用意のコード。AccountExampleSpecが初回の起動と再起動の両方を実行する。
 */
object StorageExamples {
  def sqliteSettings(): SqliteStorageConfig =
    SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.delete(2)).lockWait(Duration.ofSeconds(5)).build()

  /** First launch only: creates the tables in a new or empty file. / 初回だけ。新しい（空の）ファイルに表を作ります。 */
  def initializeSqlite(file: Path): Unit = SqliteStorage.initialize(file, sqliteSettings())

  /** Every launch: opens the initialized file. The caller closes it. / 毎回。初期化済みのファイルを開きます。閉じるのは呼出元です。 */
  def openSqlite(file: Path): SqliteStorage = SqliteStorage.open(file, sqliteSettings())

  def spannerSettings(prefix: String): SpannerStorageConfig =
    SpannerStorageConfig.builder().tablePrefix(prefix).retentionPolicy(SpannerRetentionPolicy.delete(2)).build()

  /**
   * Every launch: the operator has already created the five tables and the configuration row. / 毎回。5表と設定行は運用者が作成済みです。
   */
  def openSpanner(client: DatabaseClient, prefix: String): SpannerStorage =
    SpannerStorage.open(client, spannerSettings(prefix))

  def bigtableSettings(journalTableId: String, headTableId: String): BigtableStorageConfig =
    BigtableStorageConfig
      .builder()
      .journalTableId(journalTableId)
      .headTableId(headTableId)
      .retentionPolicy(RetentionPolicy.delete(2))
      .build()

  /**
   * First launch only: writes the two configuration rows into the tables the operator created. /
   * 初回だけ。運用者が作った表に設定行を書きます。
   */
  def initializeBigtable(data: BigtableDataClient, settings: BigtableStorageConfig): Unit =
    BigtableStorage.initialize(data, settings)

  /** Every launch: opens the initialized tables. The caller owns both clients. / 毎回。初期化済みの表を開きます。クライアントは呼出元が所有します。 */
  def openBigtable(
    data: BigtableDataClient,
    admin: BigtableTableAdminClient,
    settings: BigtableStorageConfig): BigtableStorage =
    BigtableStorage.open(data, admin, settings)
}
