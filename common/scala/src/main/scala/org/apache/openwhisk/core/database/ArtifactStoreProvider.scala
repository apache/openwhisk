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

package org.apache.openwhisk.core.database

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import spray.json.RootJsonFormat
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.spi.{Spi, SpiLoader}
import org.apache.openwhisk.core.entity.DocumentReader

import scala.reflect.ClassTag

/**
 * An Spi for providing ArtifactStore implementations
 */
trait ArtifactStoreProvider extends Spi {
  def makeStore[D <: DocumentSerializer: ClassTag](useBatching: Boolean = false)(implicit jsonFormat: RootJsonFormat[D],
                                                                                 docReader: DocumentReader,
                                                                                 actorSystem: ActorSystem,
                                                                                 logging: Logging): ArtifactStore[D]

  protected def getAttachmentStore[D <: DocumentSerializer: ClassTag]()(implicit
                                                                        actorSystem: ActorSystem,
                                                                        logging: Logging): Option[AttachmentStore] = {
    if (ConfigFactory.load().hasPath("whisk.spi.AttachmentStoreProvider")) {
      Some(SpiLoader.get[AttachmentStoreProvider].makeStore[D]())
    } else {
      None
    }
  }
}
