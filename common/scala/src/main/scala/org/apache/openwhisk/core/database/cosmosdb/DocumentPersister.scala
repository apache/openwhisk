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

package org.apache.openwhisk.core.database.cosmosdb

import akka.Done
import akka.stream.ActorMaterializer
import kamon.metric.Gauge
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.DocInfo
import spray.json.JsObject

import scala.concurrent.{ExecutionContext, Future}

trait DocumentPersister {
  def put(js: JsObject)(implicit transid: TransactionId): Future[DocInfo]
  def close(): Future[Done] = Future.successful(Done)
}

class SimplePersister(store: CosmosDBArtifactStore[_]) extends DocumentPersister {
  override def put(js: JsObject)(implicit transid: TransactionId): Future[DocInfo] = store.putJsonDoc(js)
}

class QueuedPersister(store: CosmosDBArtifactStore[_], config: WriteQueueConfig, gauge: Option[Gauge])(
  implicit materializer: ActorMaterializer,
  ec: ExecutionContext)
    extends DocumentPersister {
  private val queuedExecutor =
    new QueuedExecutor[(JsObject, TransactionId), DocInfo](config.queueSize, config.concurrency)({
      case (js, tid) => store.putJsonDoc(js)(tid)
    })

  override def put(js: JsObject)(implicit transid: TransactionId): Future[DocInfo] = queuedExecutor.put((js, transid))

  override def close(): Future[Done] = queuedExecutor.close()
}
