/*
 * Copyright 2023 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event_store_adatpter_java.Aggregate

import java.time.Instant
import java.util.UUID

final case class UserAccount private (
    id: UserAccountId,
    private var _sequenceNumber: Long,
    name: String,
    private var _version: Long
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
