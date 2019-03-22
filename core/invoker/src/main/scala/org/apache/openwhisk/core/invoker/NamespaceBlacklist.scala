/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.invoker

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.StaleParameter
import org.apache.openwhisk.core.entity.{Identity, View}
import org.apache.openwhisk.core.entity.types.AuthStore

import scala.concurrent.{ExecutionContext, Future}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration.FiniteDuration

/**
 * The namespace blacklist gets all namespaces that are throttled to 0 or blocked from the database.
 *
 * The caller is responsible for periodically updating the blacklist with `refreshBlacklist`.
 *
 * @param authStore Subjects database with the limit-documents.
 */
class NamespaceBlacklist(authStore: AuthStore) {

  private var blacklist: Set[String] = Set.empty

  /**
   * Check if the identity, who invoked the activation is in the blacklist.
   *
   * @param identity which invoked the action.
   * @return whether or not the current identity is considered blacklisted
   */
  def isBlacklisted(identity: Identity): Boolean = blacklist.contains(identity.namespace.name.asString)

  /** Refreshes the current blacklist from the database. */
  /** Limit query parameter set to 0 for limitless record query. */
  def refreshBlacklist()(implicit ec: ExecutionContext, tid: TransactionId): Future[Set[String]] = {
    authStore
      .query(
        table = NamespaceBlacklist.view.name,
        startKey = List.empty,
        endKey = List.empty,
        skip = 0,
        limit = 0,
        includeDocs = false,
        descending = true,
        reduce = false,
        stale = StaleParameter.UpdateAfter)
      .map(_.map(_.fields("key").convertTo[String]).toSet)
      .map { newBlacklist =>
        blacklist = newBlacklist
        newBlacklist
      }
  }
}

object NamespaceBlacklist {
  val view = View("namespaceThrottlings", "blockedNamespaces")
}

/** Configuration relevant to the namespace blacklist */
case class NamespaceBlacklistConfig(pollInterval: FiniteDuration)
