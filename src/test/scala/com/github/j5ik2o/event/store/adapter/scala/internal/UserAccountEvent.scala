package com.github.j5ik2o.event.store.adapter.scala.internal

import com.fasterxml.jackson.annotation.{ JsonIgnoreProperties, JsonSubTypes, JsonTypeInfo, JsonTypeName }
import com.github.j5ik2o.event_store_adatpter_java.Event

import java.time.Instant

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(name = "created", value = classOf[UserAccountEvent.Created]),
    new JsonSubTypes.Type(name = "renamed", value = classOf[UserAccountEvent.Renamed])
  )
)
sealed trait UserAccountEvent extends Event[UserAccountId] {
  override def getAggregateId: UserAccountId

  override def getSequenceNumber: Long
}

object UserAccountEvent {
  @JsonTypeName("created")
  @JsonIgnoreProperties(value = Array("created"), allowGetters = true)
  final case class Created(
      private val id: String,
      private val aggregateId: UserAccountId,
      private val sequenceNumber: Long,
      private val name: String,
      private val occurredAt: Instant
  ) extends UserAccountEvent {
    override def isCreated                     = true
    override def getId: String                 = id
    override def getAggregateId: UserAccountId = aggregateId
    override def getSequenceNumber: Long       = sequenceNumber
    def getName: String                        = name
    override def getOccurredAt: Instant        = occurredAt
  }

  @JsonTypeName("renamed")
  @JsonIgnoreProperties(value = Array("created"), allowGetters = true)
  final case class Renamed(
      private val id: String,
      private val aggregateId: UserAccountId,
      private val sequenceNumber: Long,
      private val name: String,
      private val occurredAt: Instant
  ) extends UserAccountEvent {
    override def isCreated                     = false
    override def getId: String                 = id
    override def getAggregateId: UserAccountId = aggregateId
    def getName: String                        = name
    override def getSequenceNumber: Long       = sequenceNumber
    override def getOccurredAt: Instant        = occurredAt
  }
}
