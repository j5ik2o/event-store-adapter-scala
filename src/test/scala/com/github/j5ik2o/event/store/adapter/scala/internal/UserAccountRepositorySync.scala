package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.scala.EventStore

import scala.util.{ Success, Try }

class UserAccountRepositorySync(
    eventStoreForDynamoDB: EventStore[UserAccountId, UserAccount, UserAccountEvent]
) {

  def store(userAccountEvent: UserAccountEvent, version: Long): Try[Unit] =
    eventStoreForDynamoDB.persistEvent(userAccountEvent, version)

  def store(userAccountEvent: UserAccountEvent, userAccount: UserAccount): Try[Unit] =
    eventStoreForDynamoDB.persistEventAndSnapshot(userAccountEvent, userAccount)

  def findById(id: UserAccountId): Try[Option[UserAccount]] = {
    eventStoreForDynamoDB.getLatestSnapshotById(classOf[UserAccount], id).flatMap {
      case Some((userAccount, version)) =>
        eventStoreForDynamoDB
          .getEventsByIdSinceSequenceNumber(classOf[UserAccountEvent], id, userAccount.sequenceNumber).map { events =>
            Some(UserAccount.replay(events, userAccount, version))
          }
      case None =>
        Success(None)
    }
  }

}
