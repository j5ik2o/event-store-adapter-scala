package com.github.j5ik2o.event.store.adapter.scala.internal

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.event_store_adatpter_java.AggregateId

final case class UserAccountId(@JsonProperty("value") value: String) extends AggregateId {
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  override def getTypeName = "UserAccount"

  override def getValue: String = value

  override def asString(): String = value
}
