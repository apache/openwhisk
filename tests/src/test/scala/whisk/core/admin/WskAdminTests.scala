/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import common.WskAdmin
import whisk.core.entity.AuthKey
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAuth

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
        val auth = WhiskAuth(Subject(), AuthKey())
        val subject = auth.subject.asString
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
            wskadmin.cli(Seq("user", "create", subject, "-ns", newspace, "-u", auth.authkey.compact))

            whisk.utils.retry({
                // reverse lookup by namespace
                wskadmin.cli(Seq("user", "list", "-k", newspace)).stdout.trim should be(auth.authkey.compact)
            }, 10, Some(1.second))

            wskadmin.cli(Seq("user", "get", subject, "-ns", newspace)).stdout.trim should be(auth.authkey.compact)

            // delete namespace
            wskadmin.cli(Seq("user", "delete", subject, "-ns", newspace)).stdout should include("Namespace deleted")
        } finally {
            wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")
        }
    }

}
