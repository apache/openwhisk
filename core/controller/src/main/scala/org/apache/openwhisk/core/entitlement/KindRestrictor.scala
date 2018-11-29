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

package org.apache.openwhisk.core.entitlement

import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.entity.Identity

/**
 * The runtimes manifest specifies all runtimes enabled for the deployment.
 * Not all runtimes are available for all subject however.
 *
 * A subject is entitled to a runtime (kind) if:
 * 1. they are explicitly granted rights to it in their identity record, or
 * 2. the runtime kind is whitelisted
 *
 * If a white list is not specified (i.e., whitelist == None), then all runtimes are allowed.
 * In other words, no whitelist is the same as setting the white list to all allowed runtimes.
 *
 * @param whitelist set of default allowed kinds when not explicitly available via namespace limits.
 */
case class KindRestrictor(whitelist: Option[Set[String]] = None)(implicit logging: Logging) {

  logging.info(
    this, {
      whitelist
        .map {
          case list if list.nonEmpty => s"white-listed kinds: ${list.mkString(", ")}"
          case _                     => "no kinds are allowed, the white-list is empty"
        }
        .getOrElse("all kinds are allowed, the white-list is not specified")
    })(TransactionId.controller)

  def check(user: Identity, kind: String): Boolean = {
    val kindList = user.limits.allowedKinds.getOrElse(Set.empty).union(whitelist.getOrElse(Set.empty))
    kindList.isEmpty || kindList.contains(kind)
  }

}

object KindRestrictor {
  def apply(whitelist: Set[String])(implicit logging: Logging) = new KindRestrictor(Some(whitelist))
}
