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
        val subject = auth.subject()

        println(s"CRD subject: $subject")
        val create = wskadmin.cli(Seq("user", "create", subject))
        val get = wskadmin.cli(Seq("user", "get", subject))
        create.stdout should be(get.stdout)

        val authkey = get.stdout.trim
        authkey should include(":")
        authkey.split(":")(0).length should be(36)
        authkey.split(":")(1).length should be >= 64

        wskadmin.cli(Seq("user", "whois", authkey)).stdout.trim should be(Seq(s"subject: $subject", s"namespace: $subject").mkString("\n"))
        wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")

        val recreate = wskadmin.cli(Seq("user", "create", subject, "-u", auth.authkey.compact))
        wskadmin.cli(Seq("user", "get", subject)).stdout.trim should be(auth.authkey.compact)
        wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")
    }

}
