# event-store-adapter-scala

[![CI](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-scala/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-scala_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-scala_2.13)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-scala)](https://github.com/XAMPPRocky/tokei)

This library(Scala wrapper for [j5ik2o/event-store-adapter-java](https://github.com/j5ik2o/event-store-adapter-java)) is designed to turn DynamoDB into an Event Store for Event Sourcing.

[日本語](./README.ja.md)

## Installation

Add the following to your `build.sbt` (2.12.x, 2.13.x, 3.0.x):

```scala
val version = "..."

libraryDependencies += Seq(
  "com.github.j5ik2o" %% "event-store-adapter-scala" % version,
)
```

## Usage

You can easily implement an Event Sourcing-enabled repository using EventStore.

```scala
class UserAccountRepositoryAsync(
    eventStoreAsync: EventStoreAsync[UserAccountId, UserAccount, UserAccountEvent]
) {

  def store(userAccountEvent: UserAccountEvent, version: Long)
    (implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsync.persistEvent(userAccountEvent, version)

  def store(userAccountEvent: UserAccountEvent, userAccount: UserAccount)
    (implicit ec: ExecutionContext): Future[Unit] =
    eventStoreAsync.persistEventAndSnapshot(userAccountEvent, userAccount)

  def findById(id: UserAccountId)
    (implicit ec: ExecutionContext): Future[Option[UserAccount]] = {
    eventStoreAsync.getLatestSnapshotById(classOf[UserAccount], id).flatMap {
      case Some((userAccount, version)) =>
        eventStoreAsync
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

The following is an example of the repository usage.

```scala
val eventStore = EventStoreAsync.ofDynamoDB[UserAccountId, UserAccount, UserAccountEvent](
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

## Table Specifications

See [docs/DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md).

## License.

MIT License. See [LICENSE](LICENSE) for details.

## Other language implementations

- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
- [for JavaScript/TypeScript](https://github.com/j5ik2o/event-store-adapter-js)
