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

import scala.concurrent.Future

import akka.actor.ActorSystem
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.entity.CacheKey
import whisk.core.entity.DocInfo
import whisk.spi.Spi

/**
 * An Spi for providing ArtifactStore implementations
 */

trait ArtifactStoreProvider extends Spi {
    def makeStore[D <: DocumentSerializer, CacheAbstraction](config: WhiskConfig, name: WhiskConfig => String, cache: Option[WhiskCache[CacheAbstraction, DocInfo]])(
        implicit jsonFormat: RootJsonFormat[D],
        actorSystem: ActorSystem,
        logging: Logging): ArtifactStore[D, CacheAbstraction]

    def makeCache[CacheAbstraction](changeCacheCallback: CacheKey => Future[Unit]): WhiskCache[CacheAbstraction, DocInfo] = {
        new MultipleReadersSingleWriterCache[CacheAbstraction, DocInfo](changeCacheCallback: CacheKey => Future[Unit])
    }
}
