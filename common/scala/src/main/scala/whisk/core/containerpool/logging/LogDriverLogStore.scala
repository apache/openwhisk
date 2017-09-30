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

package whisk.core.containerpool.logging

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Future
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.entity.ActivationLogs
import whisk.core.entity.ExecutableWhiskAction
import whisk.core.entity.WhiskActivation

/** Docker log driver based LogStore impl. Uses docker log driver to emit container logs to an external store.
 * Fetching logs from that external store is not provided in this trait.
 */
class LogDriverLogStore extends LogStore {
  val config = ConfigFactory.load()
  val logDriverMessage = config.getString("whisk.logstore.log-driver-message")
  val logParameters = mutable.Map[String, mutable.Set[String]]()

  config
    .getObjectList("whisk.logstore.log-driver-opts")
    .asScala
    .foreach(c => {
      c.entrySet()
        .asScala
        .foreach(e => logParameters.getOrElse(e.getKey, mutable.Set[String]()).add(e.getValue.unwrapped().toString))
    })

  logParameters.foreach(
    e =>
      require(
        e._1 == "--log-opt" || e._1 == "--log-driver",
        s"Only --log_opt and --log_driver options may be set (found ${e._1})"))

  override def containerParameters = logParameters.map(kv => (kv._1, kv._2.toSet)).toMap
  def collectLogs(transid: TransactionId, container: Container, action: ExecutableWhiskAction): Future[Vector[String]] =
    Future.successful(Vector()) //no logs collected when using docker log drivers

  def logs(activation: WhiskActivation): Future[ActivationLogs] =
    Future.successful(ActivationLogs(Vector(logDriverMessage)))

}

object LogDriverLogStoreProvider extends LogStoreProvider {
  val logStore = new LogDriverLogStore()
  override def logStore(actorSystem: ActorSystem) = logStore
}
