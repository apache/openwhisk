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

package whisk.core.database

import whisk.core.entity._

import scala.util.Random

trait ArtifactNamingHelper {

  protected val prefix = s"artifactTCK_${Random.alphanumeric.take(4).mkString}"

  protected def aname() = EntityName(s"${prefix}_name_${randomString()}")

  protected def newNS() = EntityPath(s"${prefix}_ns_${randomString()}")

  protected def newAction(ns: EntityPath): WhiskAction = {
    WhiskAction(ns, aname(), exec)
  }

  protected def newAuth() = {
    val subject = Subject()
    val namespaces = Set(wskNS("foo"))
    WhiskAuth(subject, namespaces)
  }

  protected def wskNS(name: String) = {
    val uuid = UUID()
    WhiskNamespace(Namespace(EntityName(name), uuid), BasicAuthenticationAuthKey(uuid, Secret()))
  }

  private def randomString() = Random.alphanumeric.take(5).mkString

  private val exec = BlackBoxExec(ExecManifest.ImageName("image"), None, None, native = false)

}
