package com.github.j5ik2o.event.store.adapter.scala.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.j5ik2o.event.store.adapter.java.core.{EventEnvelope, EventStoreConfig, PayloadSerializer}

import java.time.Instant

final case class UserAccount(name: String) {
  def changeName(id: UserAccountId, nextSeqNr: Long, name: String): (UserAccount, EventEnvelope[UserAccountEvent]) =
    (copy(name = name), UserAccount.event(id, nextSeqNr, UserAccountEvent.Renamed(name)))
}

object UserAccount {
  private val mapper: ObjectMapper = new ObjectMapper()

  val config: EventStoreConfig[UserAccountEvent, UserAccount] =
    EventStoreConfig
      .builder[UserAccountEvent, UserAccount]()
      .payloadSerializer(new PayloadSerializer[UserAccountEvent] {
        override def serialize(value: UserAccountEvent): Array[Byte] = {
          val (kind, name) = value match {
            case UserAccountEvent.Created(name) => ("created", name)
            case UserAccountEvent.Renamed(name) => ("renamed", name)
          }
          mapper.writeValueAsBytes(mapper.createObjectNode().put("type", kind).put("name", name))
        }

        override def deserialize(bytes: Array[Byte]): UserAccountEvent = {
          val value = mapper.readTree(bytes)
          value.path("type").asText() match {
            case "created" => UserAccountEvent.Created(value.path("name").asText())
            case "renamed" => UserAccountEvent.Renamed(value.path("name").asText())
            case kind => throw new IllegalArgumentException(s"Unknown account event: $kind")
          }
        }
      })
      .snapshotSerializer(new PayloadSerializer[UserAccount] {
        override def serialize(value: UserAccount): Array[Byte] =
          mapper.writeValueAsBytes(mapper.createObjectNode().put("name", value.name))

        override def deserialize(bytes: Array[Byte]): UserAccount =
          UserAccount(mapper.readTree(bytes).path("name").asText())
      })
      .build()

  def event(id: UserAccountId, seqNr: Long, payload: UserAccountEvent): EventEnvelope[UserAccountEvent] =
    EventEnvelope
      .builder[UserAccountEvent]()
      .aggregateId(id.toJava)
      .seqNr(seqNr)
      .occurredAt(Instant.parse("2026-10-10T00:00:00.123456789Z"))
      .payload(payload)
      .build()

  def create(id: UserAccountId, name: String): (UserAccount, EventEnvelope[UserAccountEvent]) =
    (UserAccount(name), event(id, 1L, UserAccountEvent.Created(name)))

  def replay(events: Seq[EventEnvelope[UserAccountEvent]], snapshot: Option[UserAccount]): Option[UserAccount] =
    events.foldLeft(snapshot) { (state, envelope) =>
      envelope.payload() match {
        case UserAccountEvent.Created(name) => Some(UserAccount(name))
        case UserAccountEvent.Renamed(name) =>
          Some(
            state.getOrElse(throw new IllegalStateException("Account must be created before rename")).copy(name = name))
      }
    }
}
