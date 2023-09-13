package com.github.j5ik2o.event.store.adapter.scala.internal

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.event.store.adapter.java.Aggregate

import java.time.Instant
import java.util.UUID

final case class UserAccount private (
    @JsonProperty("id") id: UserAccountId,
    @JsonProperty("sequenceNumber") private var _sequenceNumber: Long,
    @JsonProperty("name") name: String,
    @JsonProperty("version") private var _version: Long
) extends Aggregate[UserAccountId] {

  override def getId: UserAccountId = id

  override def getSequenceNumber: Long = sequenceNumber

  override def getVersion: Long = version

  def sequenceNumber: Long = _sequenceNumber
  def version: Long        = _version

  def applyEvent(event: UserAccountEvent): UserAccount = {
    event match {
      case UserAccountEvent.Renamed(_, _, _, name, _) =>
        changeName(name)._1
      case _ => this
    }
  }

  def changeName(name: String): (UserAccount, UserAccountEvent.Renamed) = {
    val updated = copy(name = name)
    updated._sequenceNumber += 1
    (updated, UserAccountEvent.Renamed(UUID.randomUUID().toString, id, updated._sequenceNumber, name, Instant.now()))
  }

}

object UserAccount {

  def create(id: UserAccountId, name: String): (UserAccount, UserAccountEvent) = {
    val userAccount = UserAccount(id, 0L, name, 1L)
    userAccount._sequenceNumber += 1
    (userAccount, UserAccountEvent.Created(UUID.randomUUID().toString, id, 0L, name, Instant.now()))
  }

  def replay(events: Seq[UserAccountEvent], snapshot: UserAccount, version: Long): UserAccount = {
    val result = events.foldLeft(snapshot) { (acc, event) =>
      acc.applyEvent(event)
    }
    result._version = version
    result
  }

}
