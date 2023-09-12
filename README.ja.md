# event-store-adapter-scala

[![CI](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-scala_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-scala_2.13)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-scala)](https://github.com/XAMPPRocky/tokei)

このライブラリは、DynamoDBをEvent Sourcing用のEvent Storeにするためのものです。

[English](./README.md)

## Installation

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
    eventStoreAsyncForDynamoDB: EventStoreAsyncForDynamoDB[UserAccountId, UserAccount, UserAccountEvent]
) {

  def store(userAccountEvent: UserAccountEvent, version: Long)
    (implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsyncForDynamoDB.persistEvent(userAccountEvent, version)

  def store(userAccountEvent: UserAccountEvent, userAccount: UserAccount)
    (implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsyncForDynamoDB.persistEventAndSnapshot(userAccountEvent, userAccount)

  def findById(id: UserAccountId)
    (implicit ec: ExecutionContext): Future[Option[UserAccount]] = {
    eventStoreAsyncForDynamoDB.getLatestSnapshotById(classOf[UserAccount], id).flatMap {
      case Some((userAccount, version)) =>
        eventStoreAsyncForDynamoDB
          .getEventsByIdSinceSequenceNumber(
            classOf[UserAccountEvent], id, userAccount.sequenceNumber).map { events =>
            Some(UserAccount.replay(events, userAccount, version))
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
