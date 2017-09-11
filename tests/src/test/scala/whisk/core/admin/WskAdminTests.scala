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

package whisk.core.admin

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.RunWskAdminCmd
import common.TestHelpers
import common.Wsk
import common.WskAdmin
import common.WskProps
import whisk.core.entity.AuthKey
import whisk.core.entity.Subject
import common.TestUtils

@RunWith(classOf[JUnitRunner])
class WskAdminTests
    extends TestHelpers
    with Matchers {

    behavior of "Wsk Admin CLI"

    it should "confirm wskadmin exists" in {
        WskAdmin.exists
    }

    it should "CRD a subject" in {
        val wskadmin = new RunWskAdminCmd {}
        val auth = AuthKey()
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

            wskadmin.cli(Seq("user", "whois", authkey)).stdout.trim should be(Seq(s"subject: $subject", s"namespace: $subject").mkString("\n"))

            whisk.utils.retry({
                // reverse lookup by namespace
                wskadmin.cli(Seq("user", "list", "-k", subject)).stdout.trim should be(authkey)
            }, 10, Some(1.second))

            wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")

            // recreate with explicit
            val newspace = s"${subject}.myspace"
            wskadmin.cli(Seq("user", "create", subject, "-ns", newspace, "-u", auth.compact))

            whisk.utils.retry({
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

    it should "verify guest account installed correctly" in {
        val wskadmin = new RunWskAdminCmd {}
        implicit val wskprops = WskProps()
        val wsk = new Wsk
        val ns = wsk.namespace.whois()
        wskadmin.cli(Seq("user", "get", ns)).stdout.trim should be(wskprops.authKey)
    }

    it should "block and unblock a user respectively" in {
        val wskadmin = new RunWskAdminCmd {}
        val auth = AuthKey()
        val subject1 = Subject().asString
        val subject2 = Subject().asString
        val commonNamespace = "testspace"
        try {
            wskadmin.cli(Seq("user", "create", subject1, "-ns", commonNamespace, "-u", auth.compact))
            wskadmin.cli(Seq("user", "create", subject2, "-ns", commonNamespace))

            whisk.utils.retry({
                // reverse lookup by namespace
                val out = wskadmin.cli(Seq("user", "list", "-p", "2", "-k", commonNamespace)).stdout.trim
                out should include(auth.compact)
                out.lines should have size 2
            }, 10, Some(1.second))

            // block the user
            wskadmin.cli(Seq("user", "block", subject1))

            // wait until the user can no longer be found
            whisk.utils.retry({
                wskadmin.cli(Seq("user", "list", "-p", "2", "-k", commonNamespace)).stdout.trim.lines should have size 1
            }, 10, Some(1.second))

            // unblock the user
            wskadmin.cli(Seq("user", "unblock", subject1))

            // wait until the user can be found again
            whisk.utils.retry({
                val out = wskadmin.cli(Seq("user", "list", "-p", "2", "-k", commonNamespace)).stdout.trim
                out should include(auth.compact)
                out.lines should have size 2
            }, 10, Some(1.second))
        } finally {
            wskadmin.cli(Seq("user", "delete", subject1)).stdout should include("Subject deleted")
            wskadmin.cli(Seq("user", "delete", subject2)).stdout should include("Subject deleted")
        }
    }

    it should "not allow edits on a blocked subject" in {
        val wskadmin = new RunWskAdminCmd {}
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
        val wskadmin = new RunWskAdminCmd {}
        val subject = Subject().asString
        try {
            // set some limits
            wskadmin.cli(Seq("limits", "set", subject, "--invocationsPerMinute", "1", "--firesPerMinute", "2", "--concurrentInvocations", "3"))
            // check correctly set
            val lines = wskadmin.cli(Seq("limits", "get", subject)).stdout.lines.toSeq
            lines should have size 3
            lines(0) shouldBe "invocationsPerMinute = 1"
            lines(1) shouldBe "firesPerMinute = 2"
            lines(2) shouldBe "concurrentInvocations = 3"
        } finally {
            wskadmin.cli(Seq("limits", "delete", subject)).stdout should include("Limits deleted")
        }
    }
}
