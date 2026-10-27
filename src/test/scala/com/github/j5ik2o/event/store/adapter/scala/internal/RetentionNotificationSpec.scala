package com.github.j5ik2o.event.store.adapter.scala.internal

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.github.j5ik2o.event.store.adapter.java.bigtable.{
  BigtableEmulator,
  BigtableEventStore,
  FailingRetentionStorage => BigtableFailing,
}
import com.github.j5ik2o.event.store.adapter.java.core.{
  EventStoreConfig,
  RetentionFailure,
  RetentionFailureListener,
  RetentionPolicy,
}
import com.github.j5ik2o.event.store.adapter.java.spanner.{
  FailingRetentionStorage => SpannerFailing,
  SpannerEmulator,
  SpannerEventStore,
  SpannerRetentionPolicy,
  SpannerStorage,
}
import com.github.j5ik2o.event.store.adapter.java.sqlite.{SqliteEventStore, SqliteStorage, SqliteStorageConfig}
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}
import org.scalatest.freespec.AnyFreeSpec
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/**
 * A retention failure must never turn an already committed append into a failure, even when the listener or the log
 * throws an Error. Every case asserts that the failure path was really reached. /
 * 保持処理の失敗の通知先やログがErrorを投げても、確定済みの追記は成功のままである。どの試験も、失敗の経路が実際に呼ばれたことを確かめる。
 */
final class RetentionNotificationSpec extends AnyFreeSpec {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val timeout: FiniteDuration = 60.seconds

  /** One prepared store of a backend, with the means to inject and observe the retention failure. */
  private trait Backend {
    def name: String
    def loggerClass: Class[_]
    def sync(config: EventStoreConfig[String, String]): EventStore[String, String]
    def async(config: EventStoreConfig[String, String]): EventStoreAsync[String, String]

    /** Makes the next retention deletion fail. Returns how many times that failure was actually injected so far. */
    def injectFailure(): Unit
    def injectedFailures(): Option[Int]
    def readBack(): EventStore[String, String]
    def close(): Unit
  }

  private final class SqliteBackend extends Backend {
    private val settings: SqliteStorageConfig =
      SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.delete(1)).build()
    private val file: Path = Files.createTempDirectory("scala-sqlite-retention-").resolve("store.db")
    SqliteStorage.initialize(file, settings)
    private val storage: SqliteStorage = SqliteStorage.open(file, settings)
    override val name: String = "sqlite"
    override val loggerClass: Class[_] = classOf[SqliteEventStore]
    override def sync(config: EventStoreConfig[String, String]): EventStore[String, String] =
      EventStore.ofSqlite(storage, config).get
    override def async(config: EventStoreConfig[String, String]): EventStoreAsync[String, String] =
      Await.result(EventStoreAsync.ofSqlite(storage, config), timeout)
    override def injectFailure(): Unit = {
      val connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath)
      try
        connection
          .createStatement()
          .execute("CREATE TRIGGER fail_retention BEFORE DELETE ON snapshot_history BEGIN " +
            "SELECT RAISE(ABORT, 'injected retention failure'); END")
      finally connection.close()
    }
    override def injectedFailures(): Option[Int] = None
    override def readBack(): EventStore[String, String] = sync(StoreChecks.config)
    override def close(): Unit = storage.close()
  }

  private final class SpannerBackend extends Backend {
    private val emulator: SpannerEmulator = SpannerEmulator.shared()
    private val prefix: String = emulator.createStore(
      Option.empty[Integer].orNull,
      SpannerEmulator.ConfigRow.matching(Integer.valueOf(1), "delete", Option.empty[java.lang.Long].orNull))
    private val calls = new AtomicInteger(0)
    private val storage: SpannerStorage = SpannerFailing.open(emulator, prefix, SpannerRetentionPolicy.delete(1), calls)
    override val name: String = "spanner"
    override val loggerClass: Class[_] = classOf[SpannerEventStore]
    override def sync(config: EventStoreConfig[String, String]): EventStore[String, String] =
      EventStore.ofSpanner(storage, config).get
    override def async(config: EventStoreConfig[String, String]): EventStoreAsync[String, String] =
      Await.result(EventStoreAsync.ofSpanner(storage, config), timeout)
    override def injectFailure(): Unit = ()
    override def injectedFailures(): Option[Int] = Some(calls.get())
    override def readBack(): EventStore[String, String] = sync(StoreChecks.config)
    override def close(): Unit = ()
  }

  private final class BigtableBackend extends Backend {
    private val emulator: BigtableEmulator = BigtableEmulator.shared()
    private val tables: BigtableEmulator.Tables = emulator.createStore("1")
    private val calls = new AtomicInteger(0)
    private val storage =
      BigtableFailing.open(emulator, tables, RetentionPolicy.delete(1), calls)
    override val name: String = "bigtable"
    override val loggerClass: Class[_] = classOf[BigtableEventStore]
    override def sync(config: EventStoreConfig[String, String]): EventStore[String, String] =
      EventStore.ofBigtable(storage, config).get
    override def async(config: EventStoreConfig[String, String]): EventStoreAsync[String, String] =
      Await.result(EventStoreAsync.ofBigtable(storage, config), timeout)
    override def injectFailure(): Unit = ()
    override def injectedFailures(): Option[Int] = Some(calls.get())
    override def readBack(): EventStore[String, String] = sync(StoreChecks.config)
    override def close(): Unit = ()
  }

  /** doAppend is overridden so the Error reaches the logging caller unchanged. */
  private final class ThrowingAppender extends AppenderBase[ILoggingEvent] {
    val warnCalls = new AtomicInteger(0)
    override def doAppend(loggingEvent: ILoggingEvent): Unit =
      if (loggingEvent.getLevel == Level.WARN) {
        warnCalls.incrementAndGet()
        throw new AssertionError("log failed")
      }
    override protected def append(loggingEvent: ILoggingEvent): Unit = ()
  }

  private def assertBothAppendsAreStored(backend: Backend): Unit = {
    val store = backend.readBack()
    val head = store.getLatestSnapshotById(StoreChecks.aggregateId).get.get
    assert(head.headSeqNr == 2L)
    assert(head.snapshot.map(_.seqNr()).contains(2L))
    assert(store.getEventsByIdSinceSeqNr(StoreChecks.aggregateId, 1L).get.map(_.seqNr()) == Seq(1L, 2L))
  }

  private def check(newBackend: () => Backend, asynchronous: Boolean, viaLog: Boolean): Unit = {
    val backend = newBackend()
    val listenerCalls = new AtomicInteger(0)
    val notifications = new CopyOnWriteArrayList[RetentionFailure]()
    val listener: RetentionFailureListener =
      if (viaLog) (failure: RetentionFailure) => { notifications.add(failure); () }
      else
        (_: RetentionFailure) => {
          listenerCalls.incrementAndGet()
          throw new AssertionError("listener failed")
        }
    val config = StoreChecks.configWith(Some(listener))
    val logger = LoggerFactory.getLogger(backend.loggerClass).asInstanceOf[Logger]
    val appender = new ThrowingAppender()
    appender.setContext(logger.getLoggerContext)
    appender.start()
    if (viaLog) logger.addAppender(appender)
    try {
      val append: Long => Unit =
        if (asynchronous) {
          val store = backend.async(config)
          seq => Await.result(store.persistEventAndSnapshot(StoreChecks.event(seq), StoreChecks.snapshot(seq)), timeout)
        } else {
          val store = backend.sync(config)
          seq => store.persistEventAndSnapshot(StoreChecks.event(seq), StoreChecks.snapshot(seq)).get
        }
      append(1L)
      backend.injectFailure()
      val injectedBefore = backend.injectedFailures()
      val warnsBefore = appender.warnCalls.get()
      val notificationsBefore = notifications.size()
      val listenerBefore = listenerCalls.get()

      append(2L) // a committed append stays a success even though the retention path threw an Error

      if (viaLog) {
        assert(appender.warnCalls.get() == warnsBefore + 1, s"${backend.name}: the retention log must be called once")
        assert(notifications.size() == notificationsBefore + 1, s"${backend.name}: the listener must be called once")
      } else {
        assert(listenerCalls.get() == listenerBefore + 1, s"${backend.name}: the listener must be called once")
      }
      injectedBefore.foreach(before =>
        assert(backend.injectedFailures().contains(before + 1), s"${backend.name}: the injected failure must run once"))
      assertBothAppendsAreStored(backend)
    } finally {
      logger.detachAppender(appender)
      appender.stop()
      backend.close()
    }
  }

  private val backends: Seq[(String, () => Backend)] = Seq(
    "SQLite" -> (() => new SqliteBackend()),
    "Spanner" -> (() => new SpannerBackend()),
    "Bigtable" -> (() => new BigtableBackend()),
  )

  for ((label, newBackend) <- backends) {
    s"$label: a synchronous append succeeds when the listener throws an AssertionError" in
      check(newBackend, asynchronous = false, viaLog = false)
    s"$label: a synchronous append succeeds when the retention log throws an AssertionError" in
      check(newBackend, asynchronous = false, viaLog = true)
    s"$label: an asynchronous append completes when the listener throws an AssertionError" in
      check(newBackend, asynchronous = true, viaLog = false)
    s"$label: an asynchronous append completes when the retention log throws an AssertionError" in
      check(newBackend, asynchronous = true, viaLog = true)
  }
}
