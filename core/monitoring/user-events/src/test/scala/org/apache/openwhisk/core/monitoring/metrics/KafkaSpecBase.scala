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

package org.apache.openwhisk.core.monitoring.metrics

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import net.manub.embeddedkafka.EmbeddedKafka
import org.scalatest._
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

abstract class KafkaSpecBase
    extends TestKit(ActorSystem("test"))
    with Suite
    with Matchers
    with ScalaFutures
    with FlatSpecLike
    with EmbeddedKafka
    with IntegrationPatience
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Eventually
    with EventsTestHelper { this: Suite =>
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val timeoutConfig = PatienceConfig(1.minute)

  implicit val materializer = ActorMaterializer()

  def sleep(time: FiniteDuration, msg: String = ""): Unit = {
    log.info(s"sleeping $time $msg")
    Thread.sleep(time.toMillis)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    shutdown()
  }
}
