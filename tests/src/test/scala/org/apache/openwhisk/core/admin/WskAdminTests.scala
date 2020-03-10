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

package org.apache.openwhisk.core.admin

import common.WskAdmin.wskadmin
import common._
import common.rest.WskRestOperations
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Matchers}
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.entity.{BasicAuthenticationAuthKey, Subject}
import common.TestHelpers

import scala.concurrent.duration.DurationInt
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class WskAdminTests extends TestHelpers with WskActorSystem with Matchers with BeforeAndAfterAll {

  override def beforeAll() = {
    val testSpaces = Seq("testspace", "testspace1", "testspace2")
    testSpaces.foreach(testspace => {
      Try {
        val identities = wskadmin.cli(Seq("user", "list", "-a", testspace))
        identities.stdout
          .split("\n")
          .foreach(ident => {
            val sub = ident.split("\\s+").last
            wskadmin.cli(Seq("user", "delete", sub, "-ns", testspace))
          })
      }
    })
  }

  behavior of "Wsk Admin CLI"

  it should "confirm wskadmin exists" in {
    WskAdmin.exists
  }

  it should "CRD a subject" in {
    val auth = BasicAuthenticationAuthKey()
    val subject = Subject().asString
    try {
      println(s"CRD subject: $subject")
      val create = wskadmin.cli(Seq("user", "create", subject))
      val get = wskadmin.cli(Seq("user", "get", subject))
      create.stdout should be(get.stdout)

      val authkey = get.stdout.trim
      authkey should include(":")
      authkey.split(":")(0).length should be(36)
      authkey.split(":")(1).length should be >= 64

      wskadmin.cli(Seq("user", "whois", authkey)).stdout.trim should be(
        Seq(s"subject: $subject", s"namespace: $subject").mkString("\n"))

      org.apache.openwhisk.utils.retry({
        // reverse lookup by namespace
        wskadmin.cli(Seq("user", "list", "-k", subject)).stdout.trim should be(authkey)
      }, 10, Some(1.second))

      wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")

      // recreate with explicit
      val newspace = s"${subject}.myspace"
      wskadmin.cli(Seq("user", "create", subject, "-ns", newspace, "-u", auth.compact))

      org.apache.openwhisk.utils.retry({
        // reverse lookup by namespace
        wskadmin.cli(Seq("user", "list", "-k", newspace)).stdout.trim should be(auth.compact)
      }, 10, Some(1.second))

      wskadmin.cli(Seq("user", "get", subject, "-ns", newspace)).stdout.trim should be(auth.compact)

      // delete namespace
      wskadmin.cli(Seq("user", "delete", subject, "-ns", newspace)).stdout should include("Namespace deleted")
    } finally {
      wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")
    }
  }

  it should "list all namespaces for a subject" in {
    val auth = BasicAuthenticationAuthKey()
    val subject = Subject().asString
    try {
      println(s"CRD subject: $subject")
      val first = wskadmin.cli(Seq("user", "create", subject, "-ns", s"$subject.space1"))
      val second = wskadmin.cli(Seq("user", "create", subject, "-ns", s"$subject.space2"))
      wskadmin.cli(Seq("user", "get", subject, "--all")).stdout.trim should be {
        s"""
           |$subject.space1\t${first.stdout.trim}
           |$subject.space2\t${second.stdout.trim}
           |""".stripMargin.trim
      }
    } finally {
      wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")
    }
  }

  it should "verify guest account installed correctly" in {
    implicit val wskprops = WskProps()
    val wsk = new WskRestOperations
    val ns = wsk.namespace.whois()
    wskadmin.cli(Seq("user", "get", ns)).stdout.trim should be(wskprops.authKey)
  }

  it should "block and unblock a user respectively" in {
    val auth = BasicAuthenticationAuthKey()
    val subject1 = Subject().asString
    val subject2 = Subject().asString
    val commonNamespace = "testspace"
    try {
      wskadmin.cli(Seq("user", "create", subject1, "-ns", commonNamespace, "-u", auth.compact))
      wskadmin.cli(Seq("user", "create", subject2, "-ns", commonNamespace))

      org.apache.openwhisk.utils.retry({
        // reverse lookup by namespace
        val out = wskadmin.cli(Seq("user", "list", "-p", "2", "-k", commonNamespace)).stdout.trim
        out should include(auth.compact)
        out.linesIterator should have size 2
      }, 10, Some(1.second))

      // block the user
      wskadmin.cli(Seq("user", "block", subject1))

      // wait until the user can no longer be found
      org.apache.openwhisk.utils.retry({
        wskadmin.cli(Seq("user", "list", "-p", "2", "-k", commonNamespace)).stdout.trim.linesIterator should have size 1
      }, 10, Some(1.second))

      // unblock the user
      wskadmin.cli(Seq("user", "unblock", subject1))

      // wait until the user can be found again
      org.apache.openwhisk.utils.retry({
        val out = wskadmin.cli(Seq("user", "list", "-p", "2", "-k", commonNamespace)).stdout.trim
        out should include(auth.compact)
        out.linesIterator should have size 2
      }, 10, Some(1.second))
    } finally {
      wskadmin.cli(Seq("user", "delete", subject1)).stdout should include("Subject deleted")
      wskadmin.cli(Seq("user", "delete", subject2)).stdout should include("Subject deleted")
    }
  }

  it should "block and unblock should accept more than a single subject" in {
    val subject1 = Subject().asString
    val subject2 = Subject().asString
    try {
      wskadmin.cli(Seq("user", "create", subject1))
      wskadmin.cli(Seq("user", "create", subject2))

      // empty subjects are expected to be ignored
      wskadmin.cli(Seq("user", "block", subject1, subject2, "", " ")).stdout shouldBe {
        s"""|"$subject1" blocked successfully
            |"$subject2" blocked successfully
            |""".stripMargin
      }

      wskadmin.cli(Seq("user", "unblock", subject1, subject2, "", " ")).stdout shouldBe {
        s"""|"$subject1" unblocked successfully
            |"$subject2" unblocked successfully
            |""".stripMargin
      }
    } finally {
      wskadmin.cli(Seq("user", "delete", subject1)).stdout should include("Subject deleted")
      wskadmin.cli(Seq("user", "delete", subject2)).stdout should include("Subject deleted")
    }
  }

  it should "not allow edits on a blocked subject" in {
    val subject = Subject().asString
    try {
      // initially create the subject
      wskadmin.cli(Seq("user", "create", subject))
      // editing works
      wskadmin.cli(Seq("user", "create", subject, "-ns", "testspace1"))
      // block it
      wskadmin.cli(Seq("user", "block", subject))
      // Try to add a namespace, doesn't work
      wskadmin.cli(Seq("user", "create", subject, "-ns", "testspace2"), expectedExitCode = TestUtils.ERROR_EXIT)
      // Unblock the user
      wskadmin.cli(Seq("user", "unblock", subject))
      // Adding a namespace works
      wskadmin.cli(Seq("user", "create", subject, "-ns", "testspace2"))
    } finally {
      wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")
    }
  }

  it should "adjust throttles for namespace" in {
    val subject = Subject().asString
    try {
      // set some limits
      wskadmin.cli(
        Seq(
          "limits",
          "set",
          subject,
          "--invocationsPerMinute",
          "1",
          "--firesPerMinute",
          "2",
          "--concurrentInvocations",
          "3"))
      // check correctly set
      val lines = wskadmin.cli(Seq("limits", "get", subject)).stdout.linesIterator.toSeq
      lines should have size 3
      lines(0) shouldBe "invocationsPerMinute = 1"
      lines(1) shouldBe "firesPerMinute = 2"
      lines(2) shouldBe "concurrentInvocations = 3"
    } finally {
      wskadmin.cli(Seq("limits", "delete", subject)).stdout should include("Limits deleted")
    }
  }

  it should "disable saving of activations in ActivationsStore" in {
    val subject = Subject().asString
    try {
      // set limit
      wskadmin.cli(Seq("limits", "set", subject, "--storeActivations", "false"))
      // check correctly set
      val lines = wskadmin.cli(Seq("limits", "get", subject)).stdout.linesIterator.toSeq
      lines should have size 1
      lines(0) shouldBe "storeActivations = False"
    } finally {
      wskadmin.cli(Seq("limits", "delete", subject)).stdout should include("Limits deleted")
    }
  }

  it should "adjust whitelist for namespace" in {
    val subject = Subject().asString
    try {
      // set some limits
      wskadmin.cli(Seq("limits", "set", subject, "--allowedKinds", "nodejs:10", "blackbox"))
      // check correctly set
      val lines = wskadmin.cli(Seq("limits", "get", subject)).stdout.linesIterator.toSeq
      lines should have size 1
      lines(0) shouldBe "allowedKinds = [u'nodejs:10', u'blackbox']"
    } finally {
      wskadmin.cli(Seq("limits", "delete", subject)).stdout should include("Limits deleted")
    }
  }
}
