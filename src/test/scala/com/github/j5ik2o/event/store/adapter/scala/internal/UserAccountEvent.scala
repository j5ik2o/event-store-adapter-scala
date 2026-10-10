package com.github.j5ik2o.event.store.adapter.scala.internal

sealed trait UserAccountEvent

object UserAccountEvent {
  final case class Created(name: String) extends UserAccountEvent
  final case class Renamed(name: String) extends UserAccountEvent
}
