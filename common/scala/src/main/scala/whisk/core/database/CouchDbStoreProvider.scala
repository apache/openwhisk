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

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.entity.DocumentReader

object CouchDbStoreProvider extends ArtifactStoreProvider {

  def makeStore[D <: DocumentSerializer](config: WhiskConfig, name: WhiskConfig => String, useBatching: Boolean)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {
    require(config != null && config.isValid, "config is undefined or not valid")
    require(
      config.dbProvider == "Cloudant" || config.dbProvider == "CouchDB",
      "Unsupported db.provider: " + config.dbProvider)
    assume(
      Set(config.dbProtocol, config.dbHost, config.dbPort, config.dbUsername, config.dbPassword, name(config))
        .forall(_.nonEmpty),
      "At least one expected property is missing")

    new CouchDbRestStore[D](
      config.dbProtocol,
      config.dbHost,
      config.dbPort.toInt,
      config.dbUsername,
      config.dbPassword,
      name(config),
      useBatching)
  }
}
