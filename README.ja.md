# event-store-adapter-scala

[![CI](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-scala_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-scala_2.13)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-scala)](https://github.com/XAMPPRocky/tokei)

このライブラリ([j5ik2o/event-store-adapter-java](https://github.com/j5ik2o/event-store-adapter-java)のScalaラッパー)は、DynamoDBをCQRS/Event Sourcing用のEvent Storeにするためのものです。

[English](./README.md)

## 導入方法

以下を`build.sbt`に追加してください。

```scala
val version = "..."

libraryDependencies += Seq(
  "com.github.j5ik2o" %% "event-store-adapter-scala" % version,
)
```

## 使い方

EventStoreを使えば、Event Sourcing対応リポジトリを簡単に実装できます。

```scala
class UserAccountRepositoryAsync(
    eventStoreAsync: EventStoreAsync[UserAccountId, UserAccount, UserAccountEvent]
) {
  // イベントを追記するだけならこちら。versionにはスナップショットテーブルの現在のversionを指定してください。
  def store(userAccountEvent: UserAccountEvent, version: Long)
    (implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsync.persistEvent(userAccountEvent, version)
  // スナップショットとイベントを同時に書き込む場合はこちら。
  def store(userAccountEvent: UserAccountEvent, userAccount: UserAccount)
    (implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsync.persistEventAndSnapshot(userAccountEvent, userAccount)
  // 最新のスナップショット+差分イベントを読み込み、最新の集約を取得します。
  def findById(id: UserAccountId)
    (implicit ec: ExecutionContext): Future[Option[UserAccount]] = {
    eventStoreAsync.getLatestSnapshotById(classOf[UserAccount], id).flatMap {
      case Some(userAccount) =>
        eventStoreAsync
          .getEventsByIdSinceSequenceNumber(
            classOf[UserAccountEvent], id, userAccount.sequenceNumber).map { events =>
            Some(UserAccount.replay(events, userAccount))
          }
      case None =>
        Future.successful(None)
    }
  }

}
```

以下はリポジトリの使用例です。

```scala
val eventStore = EventStoreAsyncForDynamoDB[UserAccountId, UserAccount, UserAccountEvent](
  dynamodbClient,
  journalTableName,
  snapshotTableName,
  journalAidIndexName,
  snapshotAidIndexName,
  32
)
val repository = new UserAccountRepositoryAsync(eventStore)

val id                 = UserAccountId(UUID.randomUUID().toString)
val (aggregate, event) = UserAccount.create(id, "test-1")

val result = for {
  _ <- repository.store(event, aggregate)
  aggregate <- repository.findById(id)
} yield aggregate
```

## テーブル仕様

[docs/DATABASE_SCHEMA.ja.md](docs/DATABASE_SCHEMA.ja.md)を参照してください。

## ライセンス

MITライセンスです。詳細は[LICENSE](LICENSE)を参照してください。

## 他の言語のための実装

- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
- [for JavaScript/TypeScript](https://github.com/j5ik2o/event-store-adapter-js)
- [for .NET](https://github.com/j5ik2o/event-store-adapter-dotnet)
- [for PHP](https://github.com/j5ik2o/event-store-adapter-php)

