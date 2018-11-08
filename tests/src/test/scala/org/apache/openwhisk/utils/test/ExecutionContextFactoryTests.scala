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

package org.apache.openwhisk.utils.test

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.WskActorSystem
import org.apache.openwhisk.utils.ExecutionContextFactory.FutureExtensions

@RunWith(classOf[JUnitRunner])
class ExecutionContextFactoryTests extends FlatSpec with Matchers with WskActorSystem {

  behavior of "future extensions"

  it should "take first to complete" in {
    val f1 = Future.successful({}).withTimeout(500.millis, new Throwable("error"))
    Await.result(f1, 1.second) shouldBe ({})

    val failure = new Throwable("error")
    val f2 = Future { Thread.sleep(1.second.toMillis) }.withTimeout(500.millis, failure)
    a[Throwable] shouldBe thrownBy { Await.result(f2, 1.seconds) }
  }
}
