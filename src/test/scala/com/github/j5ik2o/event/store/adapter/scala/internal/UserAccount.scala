package com.github.j5ik2o.event.store.adapter.scala.internal

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.event.store.adapter.java.Aggregate

import java.time.Instant
import java.util.UUID

final case class UserAccount private (
  @JsonProperty("id") id: UserAccountId,
  @JsonProperty("sequenceNumber") private var _sequenceNumber: Long,
  @JsonProperty("name") name: String,
  @JsonProperty("version") private var _version: Long,
) extends Aggregate[UserAccount, UserAccountId] {

  override def getId: UserAccountId = id

  override def getSequenceNumber: Long = sequenceNumber

  override def getVersion: Long = version

  def sequenceNumber: Long = _sequenceNumber
  def version: Long = _version

  def applyEvent(event: UserAccountEvent): UserAccount =
    event match {
      case UserAccountEvent.Renamed(_, _, _, name, _) =>
        changeName(name)._1
      case _ => this
    }

  def changeName(name: String): (UserAccount, UserAccountEvent.Renamed) = {
    val updated = copy(name = name)
    updated._sequenceNumber += 1
    (updated, UserAccountEvent.Renamed(UUID.randomUUID().toString, id, updated._sequenceNumber, name, Instant.now()))
  }

  override def withVersion(version: Long): UserAccount = copy(_version = version)
}

object UserAccount {

  def create(id: UserAccountId, name: String): (UserAccount, UserAccountEvent) = {
    val userAccount = UserAccount(id, 1L, name, 1L)
    val userAccountCreated =
      UserAccountEvent.Created(UUID.randomUUID().toString, id, userAccount._sequenceNumber, name, Instant.now())
    (
      userAccount,
      userAccountCreated,
    )
  }

  def replay(events: Seq[UserAccountEvent], snapshot: UserAccount): UserAccount =
    events.foldLeft(snapshot) { (acc, event) =>
      acc.applyEvent(event)
    }

}
