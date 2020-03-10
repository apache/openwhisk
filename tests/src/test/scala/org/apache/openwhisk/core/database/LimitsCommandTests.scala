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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.cli.CommandMessages
import org.apache.openwhisk.core.database.LimitsCommand.LimitEntity
import org.apache.openwhisk.core.entity.{DocInfo, EntityName, UserLimits}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class LimitsCommandTests extends FlatSpec with WhiskAdminCliTestBase {
  private val limitsToDelete = ListBuffer[String]()

  protected val limitsStore = LimitsCommand.createDataStore()

  behavior of "limits"

  it should "set limits for non existing namespace" in {
    implicit val tid = transid()
    val ns = newNamespace()
    resultOk(
      "limits",
      "set",
      "--invocationsPerMinute",
      "3",
      "--firesPerMinute",
      "7",
      "--concurrentInvocations",
      "11",
      "--allowedKinds",
      "nodejs:10",
      "blackbox",
      "--storeActivations",
      "false",
      ns) shouldBe CommandMessages.limitsSuccessfullySet(ns)

    val limits = limitsStore.get[LimitEntity](DocInfo(LimitsCommand.limitIdOf(EntityName(ns)))).futureValue
    limits.limits shouldBe UserLimits(
      invocationsPerMinute = Some(3),
      firesPerMinute = Some(7),
      concurrentInvocations = Some(11),
      allowedKinds = Some(Set("nodejs:10", "blackbox")),
      storeActivations = Some(false))

    resultOk("limits", "set", "--invocationsPerMinute", "13", ns) shouldBe CommandMessages.limitsSuccessfullyUpdated(ns)

    val limits2 = limitsStore.get[LimitEntity](DocInfo(LimitsCommand.limitIdOf(EntityName(ns)))).futureValue
    limits2.limits shouldBe UserLimits(Some(13), None, None)
  }

  it should "set and get limits" in {
    val ns = newNamespace()
    resultOk("limits", "set", "--invocationsPerMinute", "13", ns)
    resultOk("limits", "get", ns) shouldBe "invocationsPerMinute = 13"
  }

  it should "respond with default system limits apply for non existing namespace" in {
    resultOk("limits", "get", "non-existing-ns") shouldBe CommandMessages.defaultLimits
  }

  it should "delete an existing limit" in {
    val ns = newNamespace()
    resultOk("limits", "set", "--invocationsPerMinute", "13", ns)
    resultOk("limits", "get", ns) shouldBe "invocationsPerMinute = 13"

    //Delete
    resultOk("limits", "delete", ns) shouldBe CommandMessages.limitsDeleted

    //Read after delete should result in default message
    resultOk("limits", "get", ns) shouldBe CommandMessages.defaultLimits

    //Delete of deleted namespace should result in error
    resultNotOk("limits", "delete", ns) shouldBe CommandMessages.limitsNotFound(ns)
  }

  it should "update existing allowedKind limit" in {
    val ns = newNamespace()
    resultOk("limits", "set", "--allowedKinds", "nodejs:10", ns)
    resultOk("limits", "get", ns) shouldBe "allowedKinds = nodejs:10"
    resultOk("limits", "set", "--allowedKinds", "nodejs:10", "blackbox", "python", ns)
    resultOk("limits", "get", ns) shouldBe "allowedKinds = nodejs:10, blackbox, python"

    //Delete
    resultOk("limits", "delete", ns) shouldBe CommandMessages.limitsDeleted

    //Read after delete should result in default message
    resultOk("limits", "get", ns) shouldBe CommandMessages.defaultLimits

    //Delete of deleted namespace should result in error
    resultNotOk("limits", "delete", ns) shouldBe CommandMessages.limitsNotFound(ns)
  }

  override def cleanup()(implicit timeout: Duration): Unit = {
    implicit val tid = TransactionId.testing
    limitsToDelete.map { u =>
      Try {
        val limit = limitsStore.get[LimitEntity](DocInfo(LimitsCommand.limitIdOf(EntityName(u)))).futureValue
        delete(limitsStore, limit.docinfo)
      }
    }
    limitsToDelete.clear()
    super.cleanup()
  }

  private def newNamespace(): String = {
    val ns = randomString()
    limitsToDelete += ns
    ns
  }

}
