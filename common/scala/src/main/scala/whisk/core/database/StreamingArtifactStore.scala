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
import akka.stream.scaladsl.Sink
import spray.json.JsObject
import whisk.common.{Logging, TransactionId}

import scala.concurrent.Future
import scala.reflect.ClassTag

trait StreamingArtifactStore {

  protected[database] def getAll[T](sink: Sink[JsObject, Future[T]])(implicit transid: TransactionId): Future[(Long, T)]

  protected[database] def getCount()(implicit transid: TransactionId): Future[Option[Long]]
}

trait StreamingArtifactStoreProvider {

  def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                     logging: Logging,
                                                     materializer: ActorMaterializer): StreamingArtifactStore
}
