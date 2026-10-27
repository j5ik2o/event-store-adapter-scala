package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{ConfigurationException, RetentionPolicy, StorageException}
import com.github.j5ik2o.event.store.adapter.java.sqlite.{SqliteStorage, SqliteStorageConfig}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import org.scalatest.freespec.AnyFreeSpec

import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

final class EventStoreForSqliteSpec extends AnyFreeSpec {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val settings: SqliteStorageConfig =
    SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.none()).lockWait(Duration.ofSeconds(10)).build()

  private def newFile(): Path = Files.createTempDirectory("scala-sqlite-").resolve("store.db")

  /** Opens a new storage on the file; the user owns and closes every storage. */
  private final class Opened(file: Path) {
    private val storages = new ConcurrentLinkedQueue[SqliteStorage]()
    def open(): SqliteStorage = {
      val storage = SqliteStorage.open(file, settings)
      storages.add(storage)
      storage
    }
    def closeAll(): Unit = storages.asScala.foreach(_.close())
  }

  "synchronous creation, append, reads, duplicate append and reopening on a real file" in {
    val file = newFile()
    SqliteStorage.initialize(file, settings)
    val opened = new Opened(file)
    try
      StoreChecks.verifySync(
        () => EventStore.ofSqlite(opened.open(), StoreChecks.config),
        () => { opened.closeAll(); EventStore.ofSqlite(opened.open(), StoreChecks.config) })
    finally opened.closeAll()
  }

  "asynchronous creation, append, reads, duplicate append and reopening on a real file" in {
    val file = newFile()
    SqliteStorage.initialize(file, settings)
    val opened = new Opened(file)
    try
      StoreChecks.verifyAsync(
        () => EventStoreAsync.ofSqlite(opened.open(), StoreChecks.config),
        () => { opened.closeAll(); EventStoreAsync.ofSqlite(opened.open(), StoreChecks.config) })
    finally opened.closeAll()
  }

  "the wrapper does not close the storage: a second store on the same storage reads the first store's data" in {
    val file = newFile()
    SqliteStorage.initialize(file, settings)
    val storage = SqliteStorage.open(file, settings)
    try {
      val first = EventStore.ofSqlite(storage, StoreChecks.config).get
      first.persistEvent(StoreChecks.event(1)).get
      val second = EventStore.ofSqlite(storage, StoreChecks.config).get
      assert(second.getLatestSnapshotById(StoreChecks.aggregateId).get.map(_.headSeqNr).contains(1L))
      val async = Await.result(EventStoreAsync.ofSqlite(storage, StoreChecks.config), 30.seconds)
      assert(Await.result(async.getEventsByIdSinceSeqNr(StoreChecks.aggregateId, 1L), 30.seconds).size == 1)
      second.persistEvent(StoreChecks.event(2)).get
    } finally storage.close()
  }

  "operations after the user closes the storage fail with StorageException" in {
    val file = newFile()
    SqliteStorage.initialize(file, settings)
    val storage = SqliteStorage.open(file, settings)
    val store = EventStore.ofSqlite(storage, StoreChecks.config).get
    val async = Await.result(EventStoreAsync.ofSqlite(storage, StoreChecks.config), 30.seconds)
    storage.close()
    assert(store.persistEvent(StoreChecks.event(1)).failed.get.isInstanceOf[StorageException])
    assert(store.getLatestSnapshotById(StoreChecks.aggregateId).failed.get.isInstanceOf[StorageException])
    val result: Future[Unit] = async.persistEvent(StoreChecks.event(1))
    assert(Await.ready(result, 30.seconds).value.get.failed.get.isInstanceOf[StorageException])
  }

  "null arguments are a ConfigurationException, as a Failure and a failed Future" in {
    val file = newFile()
    SqliteStorage.initialize(file, settings)
    val storage = SqliteStorage.open(file, settings)
    try {
      val noStorage = Option.empty[SqliteStorage].orNull
      assert(EventStore.ofSqlite(noStorage, StoreChecks.config).failed.get.isInstanceOf[ConfigurationException])
      assert(
        EventStore
          .ofSqlite(
            storage,
            Option.empty[com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig[String, String]].orNull)
          .failed
          .get
          .isInstanceOf[ConfigurationException])
      val creation = EventStoreAsync.ofSqlite(noStorage, StoreChecks.config)
      assert(Await.ready(creation, 30.seconds).value.get.failed.get.isInstanceOf[ConfigurationException])
    } finally storage.close()
  }
}
