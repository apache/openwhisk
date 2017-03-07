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

package whisk.core.entity.test

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.entity.ExecManifest
import whisk.core.entity.ExecManifest._

@RunWith(classOf[JUnitRunner])
class ExecManifestTests
    extends FlatSpec
    with Matchers {

    behavior of "ExecManifest"

    it should "read a valid configuration" in {
        val k1 = RuntimeManifest("k1")
        val k2 = RuntimeManifest("k2", default = Some(true))
        val p1 = RuntimeManifest("p1")
        val mf = JsObject("k" -> Set(k1, k2).toJson, "p1" -> Set(p1).toJson)
        val runtimes = ExecManifest.runtimes(mf).get

        Seq("k1", "k2", "p1").foreach {
            runtimes.knownContainerRuntimes.contains(_) shouldBe true
        }

        runtimes.knownContainerRuntimes.contains("k3") shouldBe false

        runtimes.resolveDefaultRuntime("k1") shouldBe Some(k1)
        runtimes.resolveDefaultRuntime("k2") shouldBe Some(k2)
        runtimes.resolveDefaultRuntime("p1") shouldBe Some(p1)

        runtimes.resolveDefaultRuntime("k:default") shouldBe Some(k2)
        runtimes.resolveDefaultRuntime("p1:default") shouldBe None
    }

}
