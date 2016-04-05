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

package admin

import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.RunWskAdminCmd
import common.TestHelpers
import common.WskAdmin

@RunWith(classOf[JUnitRunner])
class WskAdminBasicTests
    extends TestHelpers
    with Matchers {

    behavior of "Wsk Admin CLI"

    it should "confirm wskadmin exists" in {
        WskAdmin.exists
    }

    it should "CRD a subject" in {
        val wskadmin = new RunWskAdminCmd {}
        val rand = new scala.util.Random()
        val subject = "anon-" + rand.alphanumeric.take(27).mkString
        println(s"CRD subject: $subject")
        val create = wskadmin.cli(Seq("user", "create", subject))
        val get = wskadmin.cli(Seq("user", "get", subject))
        create.stdout should be(get.stdout)
        val authkey = get.stdout.trim
        authkey should include(":")
        authkey.length should be >= 32
        wskadmin.cli(Seq("user", "whois", authkey)).
            stdout.trim should be(subject)
        wskadmin.cli(Seq("user", "delete", subject)).
            stdout should include("Subject deleted")
    }

}
