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

package org.apache.openwhisk.core.entity.test

import java.util.concurrent.TimeUnit

import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.entity.ExecManifest
import org.apache.openwhisk.core.entity.ExecManifest._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.ByteSize

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

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
      "p/i" -> ImageName("i", None, Some("p")),
      "pre/img" -> ImageName("img", None, Some("pre")),
      "pre/img:t" -> ImageName("img", None, Some("pre"), Some("t")),
      "hostname:1234/img" -> ImageName("img", Some("hostname:1234"), None),
      "hostname:1234/img:t" -> ImageName("img", Some("hostname:1234"), None, Some("t")),
      "pre1/pre2/img" -> ImageName("img", None, Some("pre1/pre2")),
      "pre1/pre2/img:t" -> ImageName("img", None, Some("pre1/pre2"), Some("t")),
      "hostname:1234/pre1/pre2/img" -> ImageName("img", Some("hostname:1234"), Some("pre1/pre2")),
      "hostname.com:3121/pre1/pre2/img:t" -> ImageName("img", Some("hostname.com:3121"), Some("pre1/pre2"), Some("t")),
      "hostname.com:3121/pre1/pre2/img:t@sha256:77af4d6b9913e693e8d0b4b294fa62ade6054e6b2f1ffb617ac955dd63fb0182" ->
        ImageName("img", Some("hostname.com:3121"), Some("pre1/pre2"), Some("t")))
      .foreach {
        case (s, v) => ImageName.fromString(s) shouldBe Success(v)
      }

    Seq("ABC", "x:8080:10/abc", "p/a:x:y", "p/a:t@sha256:77af4d6b9").foreach { s =>
      a[DeserializationException] should be thrownBy ImageName.fromString(s).get
    }
  }

  it should "read a valid configuration without default prefix, default tag or blackbox images" in {
    val k1 = RuntimeManifest("k1", ImageName("???"))
    val k2 = RuntimeManifest("k2", ImageName("???"), default = Some(true))
    val p1 = RuntimeManifest("p1", ImageName("???"))
    val s1 = RuntimeManifest("s1", ImageName("???"), stemCells = Some(List(StemCell(2, 256.MB))))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson, "p1" -> Set(p1).toJson, "s1" -> Set(s1).toJson))
    val runtimes = ExecManifest.runtimes(mf, RuntimeManifestConfig()).get

    Seq("k1", "k2", "p1", "s1").foreach {
      runtimes.knownContainerRuntimes.contains(_) shouldBe true
    }

    runtimes.knownContainerRuntimes.contains("k3") shouldBe false

    runtimes.resolveDefaultRuntime("k1") shouldBe Some(k1)
    runtimes.resolveDefaultRuntime("k2") shouldBe Some(k2)
    runtimes.resolveDefaultRuntime("p1") shouldBe Some(p1)
    runtimes.resolveDefaultRuntime("s1") shouldBe Some(s1)

    runtimes.resolveDefaultRuntime("ks:default") shouldBe Some(k2)
    runtimes.resolveDefaultRuntime("p1:default") shouldBe Some(p1)
    runtimes.resolveDefaultRuntime("s1:default") shouldBe Some(s1)
  }

  it should "read a valid configuration where an image may omit registry, prefix or tag" in {
    val i1 = RuntimeManifest("i1", ImageName("???"))
    val i2 = RuntimeManifest("i2", ImageName("???", Some("rrr")))
    val i3 = RuntimeManifest("i3", ImageName("???", Some("rrr"), Some("ppp")), default = Some(true))
    val i4 = RuntimeManifest("i4", ImageName("???", Some("rrr"), Some("ppp"), Some("ttt")))
    val j1 = RuntimeManifest("j1", ImageName("???", None, None, Some("ttt")))
    val k1 = RuntimeManifest("k1", ImageName("???", None, Some("ppp")))
    val p1 = RuntimeManifest("p1", ImageName("???", None, Some("ppp"), Some("ttt")))
    val q1 = RuntimeManifest("q1", ImageName("???", Some("rrr"), None, Some("ttt")))
    val s1 = RuntimeManifest("s1", ImageName("???"), stemCells = Some(List(StemCell(2, 256.MB))))

    val mf =
      JsObject(
        "runtimes" -> JsObject(
          "is" -> Set(i1, i2, i3, i4).toJson,
          "js" -> Set(j1).toJson,
          "ks" -> Set(k1).toJson,
          "ps" -> Set(p1).toJson,
          "qs" -> Set(q1).toJson,
          "ss" -> Set(s1).toJson))
    val rmc = RuntimeManifestConfig()
    val runtimes = ExecManifest.runtimes(mf, rmc).get

    runtimes.resolveDefaultRuntime("i1").get.image.resolveImageName() shouldBe "???"
    runtimes.resolveDefaultRuntime("i2").get.image.resolveImageName() shouldBe "rrr/???"
    runtimes.resolveDefaultRuntime("i3").get.image.resolveImageName() shouldBe "rrr/ppp/???"
    runtimes.resolveDefaultRuntime("i4").get.image.resolveImageName() shouldBe "rrr/ppp/???:ttt"
    runtimes.resolveDefaultRuntime("j1").get.image.resolveImageName() shouldBe "???:ttt"
    runtimes.resolveDefaultRuntime("k1").get.image.resolveImageName() shouldBe "ppp/???"
    runtimes.resolveDefaultRuntime("p1").get.image.resolveImageName() shouldBe "ppp/???:ttt"
    runtimes.resolveDefaultRuntime("q1").get.image.resolveImageName() shouldBe "rrr/???:ttt"
    runtimes.resolveDefaultRuntime("s1").get.image.resolveImageName() shouldBe "???"
    runtimes.resolveDefaultRuntime("s1").get.stemCells.get(0).initialCount shouldBe 2
    runtimes.resolveDefaultRuntime("s1").get.stemCells.get(0).memory shouldBe 256.MB
  }

  it should "read a valid configuration with blackbox images but without default registry, prefix or tag" in {
    val imgs = Set(
      ImageName("???"),
      ImageName("???", Some("rrr")),
      ImageName("???", Some("rrr"), Some("ppp")),
      ImageName("???", Some("rrr"), Some("ppp"), Some("ttt")),
      ImageName("???", None, None, Some("ttt")),
      ImageName("???", None, Some("ppp")),
      ImageName("???", None, Some("ppp"), Some("ttt")),
      ImageName("???", Some("rrr"), None, Some("ttt")))

    val mf = JsObject("runtimes" -> JsObject.empty, "blackboxes" -> imgs.toJson)
    val runtimes = ExecManifest.runtimes(mf, RuntimeManifestConfig()).get

    runtimes.blackboxImages shouldBe imgs
    imgs.foreach(img => runtimes.skipDockerPull(img) shouldBe true)
    runtimes.skipDockerPull(ImageName("???", Some("aaa"))) shouldBe false
    runtimes.skipDockerPull(ImageName("???", None, Some("bbb"))) shouldBe false
  }

  it should "read a valid configuration with blackbox images, which may omit registry, prefix or tag" in {
    val imgs = List(
      ImageName("???"),
      ImageName("???", Some("rrr")),
      ImageName("???", Some("rrr"), Some("ppp")),
      ImageName("???", Some("rrr"), Some("ppp"), Some("ttt")),
      ImageName("???", None, None, Some("ttt")),
      ImageName("???", None, Some("ppp")),
      ImageName("???", None, Some("ppp"), Some("ttt")),
      ImageName("???", Some("rrr"), None, Some("ttt")))

    val mf = JsObject("runtimes" -> JsObject.empty, "blackboxes" -> imgs.toJson)
    val rmc = RuntimeManifestConfig()
    val runtimes = ExecManifest.runtimes(mf, rmc).get

    runtimes.blackboxImages shouldBe imgs.toSet

    imgs.forall(runtimes.skipDockerPull(_)) shouldBe true

    runtimes.skipDockerPull(ImageName("xxx")) shouldBe false
    runtimes.skipDockerPull(ImageName("???", Some("rrr"), Some("bbb"))) shouldBe false
    runtimes.skipDockerPull(ImageName("???", Some("rrr"), Some("ppp"), Some("test"))) shouldBe false
    runtimes.skipDockerPull(ImageName("???", None, None, Some("test"))) shouldBe false
  }

  it should "reject runtimes with multiple defaults" in {
    val k1 = RuntimeManifest("k1", ImageName("???"), default = Some(true))
    val k2 = RuntimeManifest("k2", ImageName("???"), default = Some(true))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson))

    an[IllegalArgumentException] should be thrownBy ExecManifest.runtimes(mf, RuntimeManifestConfig()).get
  }

  it should "reject finding a default when none specified for multiple versions in the same family" in {
    val k1 = RuntimeManifest("k1", ImageName("???"))
    val k2 = RuntimeManifest("k2", ImageName("???"))
    val mf = manifestFactory(JsObject("ks" -> Set(k1, k2).toJson))

    an[IllegalArgumentException] should be thrownBy ExecManifest.runtimes(mf, RuntimeManifestConfig()).get
  }

  it should "prefix image name with overrides without registry" in {
    val name = "xyz"
    ExecManifest.ImageName(name, Some(""), Some(""), Some("")).resolveImageName() shouldBe name

    Seq(
      (ExecManifest.ImageName(name), name),
      (ExecManifest.ImageName(name, None, None, Some("t")), s"$name:t"),
      (ExecManifest.ImageName(name, None, Some("pre")), s"pre/$name"),
      (ExecManifest.ImageName(name, None, Some("pre"), Some("t")), s"pre/$name:t"),
    ).foreach {
      case (image, exp) =>
        image.resolveImageName() shouldBe exp
        image.resolveImageName(Some("")) shouldBe exp
        image.resolveImageName(Some("r")) shouldBe s"r/$exp"
        image.resolveImageName(Some("r/")) shouldBe s"r/$exp"

    }
  }

  it should "prefix image name with overrides with registry" in {
    val name = "xyz"
    ExecManifest.ImageName(name, Some(""), Some(""), Some("")).resolveImageName() shouldBe name

    Seq(
      (ExecManifest.ImageName(name, Some("hostname.com")), s"hostname.com/$name"),
      (ExecManifest.ImageName(name, Some("hostname.com"), None, Some("t")), s"hostname.com/$name:t"),
      (ExecManifest.ImageName(name, Some("hostname.com"), Some("pre")), s"hostname.com/pre/$name"),
      (ExecManifest.ImageName(name, Some("hostname.com"), Some("pre"), Some("t")), s"hostname.com/pre/$name:t"),
    ).foreach {
      case (image, exp) =>
        image.resolveImageName() shouldBe exp
        image.resolveImageName(Some("")) shouldBe exp
        image.resolveImageName(Some("r")) shouldBe exp
        image.resolveImageName(Some("r/")) shouldBe exp

    }
  }

  it should "indicate image is local if it matches deployment docker prefix" in {
    val mf = JsObject.empty
    val rmc = RuntimeManifestConfig(bypassPullForLocalImages = Some(true), localImagePrefix = Some("localpre"))
    val manifest = ExecManifest.runtimes(mf, rmc)

    manifest.get.skipDockerPull(ImageName(prefix = Some("x"), name = "y")) shouldBe false
    manifest.get.skipDockerPull(ImageName(prefix = Some("localpre"), name = "y")) shouldBe true
  }

  it should "de/serialize stem cell configuration" in {
    val cell = StemCell(3, 128.MB)
    val cellAsJson = JsObject("initialCount" -> JsNumber(3), "memory" -> JsString("128 MB"))
    stemCellSerdes.write(cell) shouldBe cellAsJson
    stemCellSerdes.read(cellAsJson) shouldBe cell

    an[IllegalArgumentException] shouldBe thrownBy {
      StemCell(-1, 128.MB)
    }

    an[IllegalArgumentException] shouldBe thrownBy {
      StemCell(0, 128.MB)
    }

    an[IllegalArgumentException] shouldBe thrownBy {
      val cellAsJson = JsObject("initialCount" -> JsNumber(0), "memory" -> JsString("128 MB"))
      stemCellSerdes.read(cellAsJson)
    }

    the[IllegalArgumentException] thrownBy {
      val cellAsJson = JsObject("initialCount" -> JsNumber(1), "memory" -> JsString("128"))
      stemCellSerdes.read(cellAsJson)
    } should have message {
      ByteSize.formatError
    }
  }

  it should "parse manifest without reactive from JSON string" in {
    val json = """
                 |{ "runtimes": {
                 |    "nodef": [
                 |      {
                 |        "kind": "nodejs:14",
                 |        "default": true,
                 |        "image": {
                 |          "name": "nodejsaction"
                 |        },
                 |        "stemCells": [{
                 |          "initialCount": 1,
                 |          "memory": "128 MB"
                 |        }, {
                 |          "initialCount": 1,
                 |          "memory": "256 MB"
                 |        }]
                 |      }, {
                 |        "kind": "nodejs:12",
                 |        "deprecated": true,
                 |        "image": {
                 |          "name": "nodejsaction"
                 |        },
                 |        "stemCells": [{
                 |          "initialCount": 1,
                 |          "memory": "128 MB"
                 |        }]
                 |      }
                 |    ],
                 |    "pythonf": [{
                 |      "kind": "python",
                 |      "image": {
                 |        "name": "pythonaction"
                 |      },
                 |      "stemCells": [{
                 |        "initialCount": 2,
                 |        "memory": "256 MB"
                 |      }]
                 |    }],
                 |    "swiftf": [{
                 |      "kind": "swift",
                 |      "image": {
                 |        "name": "swiftaction"
                 |      },
                 |      "stemCells": []
                 |    }],
                 |    "phpf": [{
                 |      "kind": "php",
                 |      "image": {
                 |        "name": "phpaction"
                 |      }
                 |    }]
                 |  }
                 |}
                 |""".stripMargin.parseJson.asJsObject

    val js14 = RuntimeManifest(
      "nodejs:14",
      ImageName("nodejsaction"),
      default = Some(true),
      stemCells = Some(List(StemCell(1, 128.MB), StemCell(1, 256.MB))))
    val js12 = RuntimeManifest(
      "nodejs:12",
      ImageName("nodejsaction"),
      deprecated = Some(true),
      stemCells = Some(List(StemCell(1, 128.MB))))
    val py = RuntimeManifest("python", ImageName("pythonaction"), stemCells = Some(List(StemCell(2, 256.MB))))
    val sw = RuntimeManifest("swift", ImageName("swiftaction"), stemCells = Some(List.empty))
    val ph = RuntimeManifest("php", ImageName("phpaction"))
    val mf = ExecManifest.runtimes(json, RuntimeManifestConfig()).get

    mf shouldBe {
      Runtimes(
        Set(
          RuntimeFamily("nodef", Set(js14, js12)),
          RuntimeFamily("pythonf", Set(py)),
          RuntimeFamily("swiftf", Set(sw)),
          RuntimeFamily("phpf", Set(ph))),
        Set.empty,
        None)
    }

    mf.stemcells.flatMap {
      case (m, cells) =>
        cells.map { c =>
          (m.kind, m.image, c.initialCount, c.memory)
        }
    }.toList should contain theSameElementsAs List(
      (js14.kind, js14.image, 1, 128.MB),
      (js14.kind, js14.image, 1, 256.MB),
      (js12.kind, js12.image, 1, 128.MB),
      (py.kind, py.image, 2, 256.MB))
  }

  it should "parse manifest with reactive from JSON string" in {
    val json = """
                 |{ "runtimes": {
                 |    "nodef": [
                 |      {
                 |        "kind": "nodejs:14",
                 |        "default": true,
                 |        "image": {
                 |          "name": "nodejsaction"
                 |        },
                 |        "stemCells": [{
                 |          "initialCount": 1,
                 |          "memory": "128 MB",
                 |          "reactive": {
                 |            "minCount": 1,
                 |            "maxCount": 4,
                 |            "ttl": "2 minutes",
                 |            "threshold": 1,
                 |            "increment": 1
                 |          }
                 |        }, {
                 |          "initialCount": 1,
                 |          "memory": "256 MB",
                 |          "reactive": {
                 |            "minCount": 1,
                 |            "maxCount": 4,
                 |            "ttl": "2 minutes",
                 |            "threshold": 1,
                 |            "increment": 1
                 |           }
                 |        }]
                 |      }, {
                 |        "kind": "nodejs:12",
                 |        "deprecated": true,
                 |        "image": {
                 |          "name": "nodejsaction"
                 |        },
                 |        "stemCells": [{
                 |          "initialCount": 1,
                 |          "memory": "128 MB",
                 |          "reactive": {
                 |            "minCount": 1,
                 |            "maxCount": 4,
                 |            "ttl": "2 minutes",
                 |            "threshold": 1,
                 |            "increment": 1
                 |           }
                 |        }]
                 |      }
                 |    ],
                 |    "pythonf": [{
                 |      "kind": "python",
                 |      "image": {
                 |        "name": "pythonaction"
                 |      },
                 |      "stemCells": [{
                 |        "initialCount": 2,
                 |        "memory": "256 MB",
                 |        "reactive": {
                 |           "minCount": 1,
                 |           "maxCount": 4,
                 |           "ttl": "2 minutes",
                 |           "threshold": 1,
                 |           "increment": 1
                 |          }
                 |      }]
                 |    }],
                 |    "swiftf": [{
                 |      "kind": "swift",
                 |      "image": {
                 |        "name": "swiftaction"
                 |      },
                 |      "stemCells": []
                 |    }],
                 |    "phpf": [{
                 |      "kind": "php",
                 |      "image": {
                 |        "name": "phpaction"
                 |      }
                 |    }]
                 |  }
                 |}
                 |""".stripMargin.parseJson.asJsObject

    val reactive = Some(ReactivePrewarmingConfig(1, 4, FiniteDuration(2, TimeUnit.MINUTES), 1, 1))
    val js14 = RuntimeManifest(
      "nodejs:14",
      ImageName("nodejsaction"),
      default = Some(true),
      stemCells = Some(List(StemCell(1, 128.MB, reactive), StemCell(1, 256.MB, reactive))))
    val js12 = RuntimeManifest(
      "nodejs:12",
      ImageName("nodejsaction"),
      deprecated = Some(true),
      stemCells = Some(List(StemCell(1, 128.MB, reactive))))
    val py = RuntimeManifest("python", ImageName("pythonaction"), stemCells = Some(List(StemCell(2, 256.MB, reactive))))
    val sw = RuntimeManifest("swift", ImageName("swiftaction"), stemCells = Some(List.empty))
    val ph = RuntimeManifest("php", ImageName("phpaction"))
    val mf = ExecManifest.runtimes(json, RuntimeManifestConfig()).get

    mf shouldBe {
      Runtimes(
        Set(
          RuntimeFamily("nodef", Set(js14, js12)),
          RuntimeFamily("pythonf", Set(py)),
          RuntimeFamily("swiftf", Set(sw)),
          RuntimeFamily("phpf", Set(ph))),
        Set.empty,
        None)
    }

    mf.stemcells.flatMap {
      case (m, cells) =>
        cells.map { c =>
          (m.kind, m.image, c.initialCount, c.memory)
        }
    }.toList should contain theSameElementsAs List(
      (js14.kind, js14.image, 1, 128.MB),
      (js14.kind, js14.image, 1, 256.MB),
      (js12.kind, js12.image, 1, 128.MB),
      (py.kind, py.image, 2, 256.MB))
  }
}
