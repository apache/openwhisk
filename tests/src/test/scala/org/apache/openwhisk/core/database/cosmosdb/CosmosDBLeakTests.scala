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
import akka.stream.scaladsl.{Sink, Source}
import io.netty.util.ResourceLeakDetector
import io.netty.util.ResourceLeakDetector.Level
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.{
  BasicAuthenticationAuthKey,
  Identity,
  Namespace,
  Secret,
  Subject,
  UUID,
  WhiskAuth,
  WhiskNamespace
}
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration.DurationInt

/**
 * Performs query flow which causes leak multiple times. Post fix this test should always pass
 * By default this test is disabled
 */
@RunWith(classOf[JUnitRunner])
class CosmosDBLeakTests extends FlatSpec with CosmosDBStoreBehaviorBase {

  behavior of s"CosmosDB leak"

  private var initialLevel: Level = _

  override protected def beforeAll(): Unit = {
    RecordingLeakDetectorFactory.register()
    initialLevel = ResourceLeakDetector.getLevel
    ResourceLeakDetector.setLevel(Level.PARANOID)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    ResourceLeakDetector.setLevel(initialLevel)

    withClue("Recorded leak count should be zero") {
      RecordingLeakDetectorFactory.counter.cur shouldBe 0
    }
  }

  it should "not happen in performing subject query" ignore {
    implicit val tid: TransactionId = transid()
    val uuid = UUID()
    val ak = BasicAuthenticationAuthKey(uuid, Secret())
    val ns = Namespace(aname(), uuid)
    val subs =
      Array(WhiskAuth(Subject(), Set(WhiskNamespace(ns, ak))))
    subs foreach (put(authStore, _))

    implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 30.minutes)

    Source(1 to 500)
      .filter(_ => RecordingLeakDetectorFactory.counter.cur == 0)
      .mapAsync(5) { i =>
        if (i % 5 == 0) println(i)
        queryName(ns)
      }
      .runWith(Sink.ignore)
      .futureValue

    System.gc()

    withClue("Recorded leak count should be zero") {
      RecordingLeakDetectorFactory.counter.cur shouldBe 0
    }
  }

  def queryName(ns: Namespace)(implicit tid: TransactionId) = {
    Identity.list(authStore, List(ns.name.asString), limit = 1)
  }

}
