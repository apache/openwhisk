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

package whisk.core.entity.test

import java.io.{BufferedWriter, File, FileWriter}
import java.util.NoSuchElementException

import scala.util.{Success}
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.WhiskConfig
import whisk.core.entity.ExecManifest
import whisk.core.entity.ExecManifest._
import common.StreamLogging
import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class ExecManifestTests extends FlatSpec with WskActorSystem with StreamLogging with Matchers {

  behavior of "ExecManifest"

  private def manifestFactory(runtimes: JsObject) = {
    JsObject("runtimes" -> runtimes)
  }

  it should "parse an image name" in {
    Map(
      "i" -> ImageName("i"),
      "i:t" -> ImageName("i", tag = Some("t")),
      "i:tt" -> ImageName("i", tag = Some("tt")),
      "ii" -> ImageName("ii"),
      "ii:t" -> ImageName("ii", tag = Some("t")),
      "ii:tt" -> ImageName("ii", tag = Some("tt")),
      "p/i" -> ImageName("i", Some("p")),
      "pre/img" -> ImageName("img", Some("pre")),
      "pre/img:t" -> ImageName("img", Some("pre"), Some("t")),
      "pre1/pre2/img:t" -> ImageName("img", Some("pre1/pre2"), Some("t")),
      "pre1/pre2/img" -> ImageName("img", Some("pre1/pre2")))
      .foreach {
        case (s, v) => ImageName.fromString(s) shouldBe Success(v)
      }

    Seq("ABC", "x:8080/abc", "p/a:x:y").foreach { s =>
      a[DeserializationException] should be thrownBy ImageName.fromString(s).get
    }
  }

  it should "read a valid configuration without default prefix, default tag or blackbox images" in {
    val k1 = RuntimeManifest("k1", ImageName("???"))
    val k2 = RuntimeManifest("k2", ImageName("???"), default = Some(true))
    val p1 = RuntimeManifest("p1", ImageName("???"))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson, "p1" -> Set(p1).toJson))
    val runtimes = ExecManifest.runtimes(mf).get

    Seq("k1", "k2", "p1").foreach {
      runtimes.knownContainerRuntimes.contains(_) shouldBe true
    }

    runtimes.knownContainerRuntimes.contains("k3") shouldBe false

    runtimes.resolveDefaultRuntime("k1") shouldBe Some(k1)
    runtimes.resolveDefaultRuntime("k2") shouldBe Some(k2)
    runtimes.resolveDefaultRuntime("p1") shouldBe Some(p1)

    runtimes.resolveDefaultRuntime("ks:default") shouldBe Some(k2)
    runtimes.resolveDefaultRuntime("p1:default") shouldBe Some(p1)
  }

  it should "read a valid configuration without default prefix, default tag" in {
    val i1 = RuntimeManifest("i1", ImageName("???"))
    val i2 = RuntimeManifest("i2", ImageName("???", Some("ppp")), default = Some(true))
    val j1 = RuntimeManifest("j1", ImageName("???", Some("ppp"), Some("ttt")))
    val k1 = RuntimeManifest("k1", ImageName("???", None, Some("ttt")))

    val mf = JsObject(
      "defaultImagePrefix" -> "pre".toJson,
      "defaultImageTag" -> "test".toJson,
      "runtimes" -> JsObject("is" -> Set(i1, i2).toJson, "js" -> Set(j1).toJson, "ks" -> Set(k1).toJson))
    val runtimes = ExecManifest.runtimes(mf).get

    runtimes.resolveDefaultRuntime("i1").get.image.publicImageName shouldBe "pre/???:test"
    runtimes.resolveDefaultRuntime("i2").get.image.publicImageName shouldBe "ppp/???:test"
    runtimes.resolveDefaultRuntime("j1").get.image.publicImageName shouldBe "ppp/???:ttt"
    runtimes.resolveDefaultRuntime("k1").get.image.publicImageName shouldBe "pre/???:ttt"
  }

  it should "read a valid configuration with blackbox images but without default prefix or tag" in {
    val imgs = Set(
      ImageName("???"),
      ImageName("???", Some("ppp")),
      ImageName("???", Some("ppp"), Some("ttt")),
      ImageName("???", None, Some("ttt")))

    val mf = JsObject("runtimes" -> JsObject(), "blackboxes" -> imgs.toJson)
    val runtimes = ExecManifest.runtimes(mf).get
    runtimes.blackboxImages shouldBe imgs
  }

  it should "read a valid configuration with blackbox images, default prefix and tag" in {
    val imgs = Set(
      ImageName("???"),
      ImageName("???", Some("ppp")),
      ImageName("???", Some("ppp"), Some("ttt")),
      ImageName("???", None, Some("ttt")))

    val mf = JsObject(
      "defaultImagePrefix" -> "pre".toJson,
      "defaultImageTag" -> "test".toJson,
      "runtimes" -> JsObject(),
      "blackboxes" -> imgs.toJson)
    val runtimes = ExecManifest.runtimes(mf).get

    runtimes.blackboxImages shouldBe {
      Set(
        ImageName("???", Some("pre"), Some("test")),
        ImageName("???", Some("ppp"), Some("test")),
        ImageName("???", Some("ppp"), Some("ttt")),
        ImageName("???", Some("pre"), Some("ttt")))
    }

  }

  it should "reject runtimes with multiple defaults" in {
    val k1 = RuntimeManifest("k1", ImageName("???"), default = Some(true))
    val k2 = RuntimeManifest("k2", ImageName("???"), default = Some(true))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson))

    an[IllegalArgumentException] should be thrownBy ExecManifest.runtimes(mf).get
  }

  it should "reject finding a default when none is specified for multiple versions" in {
    val k1 = RuntimeManifest("k1", ImageName("???"))
    val k2 = RuntimeManifest("k2", ImageName("???"))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson))

    an[IllegalArgumentException] should be thrownBy ExecManifest.runtimes(mf).get
  }

  it should "prefix image name with overrides" in {
    val name = "xyz"
    ExecManifest.ImageName(name, Some(""), Some("")).publicImageName shouldBe name

    Seq(
      (ExecManifest.ImageName(name), name),
      (ExecManifest.ImageName(name, Some("pre")), s"pre/$name"),
      (ExecManifest.ImageName(name, None, Some("t")), s"$name:t"),
      (ExecManifest.ImageName(name, Some("pre"), Some("t")), s"pre/$name:t")).foreach {
      case (image, exp) =>
        image.publicImageName shouldBe exp

        image.localImageName("", "", None) shouldBe image.tag.map(t => s"$name:$t").getOrElse(s"$name:latest")
        image.localImageName("", "p", None) shouldBe image.tag.map(t => s"p/$name:$t").getOrElse(s"p/$name:latest")
        image.localImageName("r", "", None) shouldBe image.tag.map(t => s"r/$name:$t").getOrElse(s"r/$name:latest")
        image.localImageName("r", "p", None) shouldBe image.tag.map(t => s"r/p/$name:$t").getOrElse(s"r/p/$name:latest")
        image.localImageName("r", "p", Some("tag")) shouldBe s"r/p/$name:tag"
    }
  }

  it should "throw an error when configured manifest is a valid JSON, but with a missing key" in {
    val config_manifest = """{"nodejs":[{"kind":"nodejs:6","default":true,"image":{"name":"nodejs6action"}}]}"""
    val file = File.createTempFile("cxt", ".txt")
    file.deleteOnExit()

    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("runtimes.manifest=" + config_manifest + "\n")
    bw.close()

    val result = ExecManifest.initialize(new WhiskConfig(Map("runtimes.manifest" -> null), Set(), file), true)

    result should be a 'failure

    the[NoSuchElementException] thrownBy {
      result.get
    } should have message ("key not found: runtimes")
  }
}
