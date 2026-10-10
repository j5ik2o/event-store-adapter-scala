package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId

final case class UserAccountId(value: String) {
  def toJava: AggregateId = AggregateId.of("UserAccount", value)
}
