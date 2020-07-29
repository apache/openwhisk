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

package org.apache.openwhisk.core.database.test.behavior

import java.time.Instant

import akka.stream.ActorMaterializer
import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{ActivationStore, CacheChangeNotification, UserContext}
import org.apache.openwhisk.core.database.test.behavior.ArtifactStoreTestUtil.storeAvailable
import org.apache.openwhisk.core.entity._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers, Outcome}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Random, Try}

trait ActivationStoreBehaviorBase
    extends FlatSpec
    with ScalaFutures
    with Matchers
    with StreamLogging
    with WskActorSystem
    with IntegrationPatience
    with BeforeAndAfterEach {

  protected implicit val materializer: ActorMaterializer = ActorMaterializer()
  protected implicit val notifier: Option[CacheChangeNotification] = None

  def context: UserContext
  def activationStore: ActivationStore
  private val docsToDelete = ListBuffer[(UserContext, ActivationId)]()

  def storeType: String

  protected def transId() = TransactionId(Random.alphanumeric.take(32).mkString)

  override def afterEach(): Unit = {
    cleanup()
    stream.reset()
  }

  override protected def withFixture(test: NoArgTest): Outcome = {
    assume(storeAvailable(storeAvailableCheck), s"$storeType not configured or available")
    val outcome = super.withFixture(test)
    if (outcome.isFailed) {
      println(logLines.mkString("\n"))
    }
    outcome
  }

  protected def storeAvailableCheck: Try[Any] = Try(true)
  //~----------------------------------------< utility methods >

  protected def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): DocInfo = {
    val doc = activationStore.store(activation, context).futureValue
    docsToDelete.append((context, ActivationId(activation.docid.asString)))
    doc
  }

  protected def newActivation(ns: String, actionName: String, start: Long): WhiskActivation = {
    WhiskActivation(
      EntityPath(ns),
      EntityName(actionName),
      Subject(),
      ActivationId.generate(),
      Instant.ofEpochMilli(start),
      Instant.ofEpochMilli(start + 1000))
  }

  protected def newBindingActivation(ns: String, actionName: String, binding: String, start: Long): WhiskActivation = {
    WhiskActivation(
      EntityPath(ns),
      EntityName(actionName),
      Subject(),
      ActivationId.generate(),
      Instant.ofEpochMilli(start),
      Instant.ofEpochMilli(start + 1000),
      annotations = Parameters(WhiskActivation.bindingAnnotation, binding))
  }

  /**
   * Deletes all documents added to gc queue.
   */
  def cleanup()(implicit timeout: Duration = 10 seconds): Unit = {
    implicit val tid: TransactionId = transId()
    docsToDelete.map { e =>
      Try {
        Await.result(activationStore.delete(e._2, e._1), timeout)
      }
    }
    docsToDelete.clear()
  }

}
