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

import java.util.Base64

import scala.BigInt
import scala.Vector
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.language.reflectiveCalls
import scala.util.Try

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.entitlement.Privilege
import whisk.core.entity._
import whisk.core.entity.size.SizeInt

@RunWith(classOf[JUnitRunner])
class SchemaTests extends FlatSpec with BeforeAndAfter with Matchers {

    behavior of "AuthKey"

    it should "accept well formed keys" in {
        val uuid = UUID()
        val secret = Secret()
        Seq(s"$uuid:$secret", s" $uuid: $secret", s"$uuid:$secret ", s" $uuid : $secret ").foreach { i =>
            val k = AuthKey(i)
            assert(k.uuid == uuid)
            assert(k.key == secret)
        }
    }

    it should "reject malformed ids" in {
        Seq(null, "", " ", ":", " : ", " :", ": ", "a:b").foreach {
            i => an[IllegalArgumentException] should be thrownBy AuthKey(i)
        }
    }

    behavior of "Privilege"

    it should "serdes a right" in {
        Privilege.serdes.read("READ".toJson) shouldBe Privilege.READ
        Privilege.serdes.read("read".toJson) shouldBe Privilege.READ
        a[DeserializationException] should be thrownBy Privilege.serdes.read("???".toJson)
    }

    behavior of "Identity"

    it should "serdes an identity" in {
        val i = WhiskAuth(Subject(), AuthKey()).toIdentity
        val expected = JsObject(
            "subject" -> i.subject.asString.toJson,
            "namespace" -> i.namespace.toJson,
            "authkey" -> i.authkey.compact.toJson,
            "rights" -> Array("READ", "PUT", "DELETE", "ACTIVATE").toJson)
        Identity.serdes.write(i) shouldBe expected
        Identity.serdes.read(expected) shouldBe i
    }

    behavior of "DocInfo"

    it should "accept well formed doc info" in {
        Seq("a", " a", "a ").foreach { i =>
            val d = DocInfo(i)
            assert(d.id.asString == i.trim)
        }
    }

    it should "accept any string as doc revision" in {
        Seq("a", " a", "a ", "", null).foreach { i =>
            val d = DocRevision(i)
            assert(d.rev == (if (i != null) i.trim else null))
        }

        DocRevision.serdes.read(JsNull) shouldBe DocRevision()
        DocRevision.serdes.read(JsString("")) shouldBe DocRevision("")
        DocRevision.serdes.read(JsString("a")) shouldBe DocRevision("a")
        DocRevision.serdes.read(JsString(" a")) shouldBe DocRevision("a")
        DocRevision.serdes.read(JsString("a ")) shouldBe DocRevision("a")
        a[DeserializationException] should be thrownBy DocRevision.serdes.read(JsNumber(1))
    }

    it should "reject malformed doc info" in {
        Seq(null, "", " ").foreach {
            i => an[IllegalArgumentException] should be thrownBy DocInfo(i)
        }
    }

    it should "reject malformed doc ids" in {
        Seq(null, "", " ").foreach {
            i => an[IllegalArgumentException] should be thrownBy DocId(i)
        }
    }

    behavior of "EntityPath"

    it should "accept well formed paths" in {
        val paths = Seq("/a", "//a", "//a//", "//a//b//c", "//a//b/c//", "a", "a/b", "a/b/", "a@b.c", "a@b.c/", "a@b.c/d", "_a/", "_ _", "a/b/c")
        val expected = Seq("a", "a", "a", "a/b/c", "a/b/c", "a", "a/b", "a/b", "a@b.c", "a@b.c", "a@b.c/d", "_a", "_ _", "a/b/c")
        val spaces = paths.zip(expected).foreach {
            p => EntityPath(p._1).namespace shouldBe p._2
        }

        EntityPath.DEFAULT.addPath(EntityName("a")).toString shouldBe "_/a"

        EntityPath.DEFAULT.addPath(EntityPath("a")).toString shouldBe "_/a"
        EntityPath.DEFAULT.addPath(EntityPath("a/b")).toString shouldBe "_/a/b"

        EntityPath.DEFAULT.resolveNamespace(EntityName("a")) shouldBe EntityPath("a")
        EntityPath("a").resolveNamespace(EntityName("b")) shouldBe EntityPath("a")

        EntityPath("a").defaultPackage shouldBe true
        EntityPath("a/b").defaultPackage shouldBe false

        EntityPath("a").root shouldBe EntityName("a")
        EntityPath("a").last shouldBe EntityName("a")
        EntityPath("a/b").root shouldBe EntityName("a")
        EntityPath("a/b").last shouldBe EntityName("b")

        EntityPath("a").relativePath shouldBe empty
        EntityPath("a/b").relativePath shouldBe Some(EntityPath("b"))
        EntityPath("a/b/c").relativePath shouldBe Some(EntityPath("b/c"))

        EntityPath("a/b").toFullyQualifiedEntityName shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
    }

    it should "reject malformed paths" in {
        val paths = Seq(null, "", " ", "a/ ", "a/b/c ", " xxx", "xxx ", " xxx", "xxx/ ", "/", " /", "/ ", "//", "///", " / / / ", "a/b/ c", "a/ /b", " a/ b")
        paths.foreach {
            p => an[IllegalArgumentException] should be thrownBy EntityPath(p)
        }

        an[IllegalArgumentException] should be thrownBy EntityPath("a").toFullyQualifiedEntityName
    }

    behavior of "EntityName"

    it should "accept well formed names" in {
        val paths = Seq("a", "a b", "a@b.c", "_a", "_", "_ _", "a0", "a 0", "a.0", "a@@", "0", "0.0", "0.0.0", "0a", "0.a")
        val spaces = paths.foreach { n =>
            assert(EntityName(n).toString == n)
        }
    }

    it should "reject malformed names" in {
        val paths = Seq(null, "", " ", " xxx", "xxx ", "/", " /", "/ ", "0 ", "_ ", "a  ", "a \t", "a\n")
        paths.foreach {
            p => an[IllegalArgumentException] should be thrownBy EntityName(p)
        }
    }

    behavior of "FullyQualifiedEntityName"

    it should "work with paths" in {
        FullyQualifiedEntityName(EntityPath("a"), EntityName("b")).add(EntityName("c")) shouldBe
            FullyQualifiedEntityName(EntityPath("a/b"), EntityName("c"))

        FullyQualifiedEntityName(EntityPath("a"), EntityName("b")).fullPath shouldBe EntityPath("a/b")
    }

    it should "deserialize a fully qualified name without a version" in {
        val names = Seq(
            JsObject("path" -> "a".toJson, "name" -> "b".toJson),
            JsObject("path" -> "a".toJson, "name" -> "b".toJson, "version" -> "0.0.1".toJson),
            JsString("a/b"),
            JsString("n/a/b"),
            JsString("/a/b"),
            JsString("/n/a/b"),
            JsString("b")) //JsObject("namespace" -> "a".toJson, "name" -> "b".toJson))

        FullyQualifiedEntityName.serdes.read(names(0)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
        FullyQualifiedEntityName.serdes.read(names(1)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"), Some(SemVer()))
        FullyQualifiedEntityName.serdes.read(names(2)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
        FullyQualifiedEntityName.serdes.read(names(3)) shouldBe FullyQualifiedEntityName(EntityPath("n/a"), EntityName("b"))
        FullyQualifiedEntityName.serdes.read(names(4)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
        FullyQualifiedEntityName.serdes.read(names(5)) shouldBe FullyQualifiedEntityName(EntityPath("n/a"), EntityName("b"))
        a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdes.read(names(6))

        a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdesAsDocId.read(names(0))
        a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdesAsDocId.read(names(1))
        FullyQualifiedEntityName.serdesAsDocId.read(names(2)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
        FullyQualifiedEntityName.serdesAsDocId.read(names(3)) shouldBe FullyQualifiedEntityName(EntityPath("n/a"), EntityName("b"))
        FullyQualifiedEntityName.serdesAsDocId.read(names(4)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
        FullyQualifiedEntityName.serdesAsDocId.read(names(5)) shouldBe FullyQualifiedEntityName(EntityPath("n/a"), EntityName("b"))
        a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdesAsDocId.read(names(6))
    }

    behavior of "Binding"

    it should "desiarilize legacy format" in {
        val names = Seq(
            JsObject("namespace" -> "a".toJson, "name" -> "b".toJson),
            JsObject(),
            JsObject("name" -> "b".toJson),
            JsNull)

        Binding.optionalBindingDeserializer.read(names(0)) shouldBe Some(Binding(EntityName("a"), EntityName("b")))
        Binding.optionalBindingDeserializer.read(names(1)) shouldBe None
        a[DeserializationException] should be thrownBy Binding.optionalBindingDeserializer.read(names(2))
        a[DeserializationException] should be thrownBy Binding.optionalBindingDeserializer.read(names(3))
    }

    it should "serialize optional binding to empty object" in {
        Binding.optionalBindingSerializer.write(None) shouldBe JsObject()
    }

    behavior of "WhiskPackagePut"

    it should "deserialize empty request" in {
        WhiskPackagePut.serdes.read(JsObject()) shouldBe WhiskPackagePut()
        //WhiskPackagePut.serdes.read(JsObject("binding" -> JsNull)) shouldBe WhiskPackagePut()
        WhiskPackagePut.serdes.read(JsObject("binding" -> JsObject())) shouldBe WhiskPackagePut()
        //WhiskPackagePut.serdes.read(JsObject("binding" -> "a/b".toJson)) shouldBe WhiskPackagePut(binding = Some(Binding(EntityPath("a"), EntityName("b"))))
        a[DeserializationException] should be thrownBy WhiskPackagePut.serdes.read(JsObject("binding" -> JsNull))
    }

    behavior of "WhiskPackage"

    it should "not deserialize package without binding property" in {
        val pkg = WhiskPackage(EntityPath("a"), EntityName("b"))
        WhiskPackage.serdes.read(JsObject(pkg.toJson.fields + ("binding" -> JsObject()))) shouldBe pkg
        a[DeserializationException] should be thrownBy WhiskPackage.serdes.read(JsObject(pkg.toJson.fields - "binding"))
    }

    it should "serialize package with empty binding property" in {
        val pkg = WhiskPackage(EntityPath("a"), EntityName("b"))
        WhiskPackage.serdes.write(pkg) shouldBe JsObject(
            "namespace" -> "a".toJson,
            "name" -> "b".toJson,
            "binding" -> JsObject(),
            "parameters" -> Parameters().toJson,
            "version" -> SemVer().toJson,
            "publish" -> JsBoolean(false),
            "annotations" -> Parameters().toJson)
    }

    it should "serialize and deserialize package binding" in {
        val pkg = WhiskPackage(EntityPath("a"), EntityName("b"), Some(Binding(EntityName("x"), EntityName("y"))))
        val pkgAsJson = JsObject(
            "namespace" -> "a".toJson,
            "name" -> "b".toJson,
            "binding" -> JsObject("namespace" -> "x".toJson, "name" -> "y".toJson),
            "parameters" -> Parameters().toJson,
            "version" -> SemVer().toJson,
            "publish" -> JsBoolean(false),
            "annotations" -> Parameters().toJson)
        //val legacyPkgAsJson = JsObject(pkgAsJson.fields + ("binding" -> JsObject("namespace" -> "x".toJson, "name" -> "y".toJson)))
        WhiskPackage.serdes.write(pkg) shouldBe pkgAsJson
        WhiskPackage.serdes.read(pkgAsJson) shouldBe pkg
        //WhiskPackage.serdes.read(legacyPkgAsJson) shouldBe pkg
    }

    behavior of "SemVer"

    it should "parse semantic versions" in {
        val semvers = Seq("0.0.1", "1", "1.2", "1.2.3.").map { SemVer(_) }
        assert(semvers(0) == SemVer(0, 0, 1) && semvers(0).toString == "0.0.1")
        assert(semvers(1) == SemVer(1, 0, 0) && semvers(1).toString == "1.0.0")
        assert(semvers(2) == SemVer(1, 2, 0) && semvers(2).toString == "1.2.0")
        assert(semvers(3) == SemVer(1, 2, 3) && semvers(3).toString == "1.2.3")

    }

    it should "permit leading zeros but strip them away" in {
        val semvers = Seq("0.0.01", "01", "01.02", "01.02.003.").map { SemVer(_) }
        assert(semvers(0) == SemVer(0, 0, 1))
        assert(semvers(1) == SemVer(1, 0, 0))
        assert(semvers(2) == SemVer(1, 2, 0))
        assert(semvers(3) == SemVer(1, 2, 3))
    }

    it should "reject malformed semantic version" in {
        val semvers = Seq("0", "0.0.0", "00.00.00", ".1", "-1", "0.-1.0", "0.0.-1", "xyz", "", null)
        semvers.foreach { v =>
            val thrown = intercept[IllegalArgumentException] {
                SemVer(v)
            }
            assert(thrown.getMessage.contains("bad semantic version"))
        }
    }

    it should "reject negative values" in {
        an[IllegalArgumentException] should be thrownBy SemVer(-1, 0, 0)
        an[IllegalArgumentException] should be thrownBy SemVer(0, -1, 0)
        an[IllegalArgumentException] should be thrownBy SemVer(0, 0, -1)
        an[IllegalArgumentException] should be thrownBy SemVer(0, 0, 0)
    }

    behavior of "Exec"

    it should "properly deserialize and reserialize JSON" in {
        val b64Body = """ZnVuY3Rpb24gbWFpbihhcmdzKSB7IHJldHVybiBhcmdzOyB9Cg=="""

        val json = Seq[JsObject](
            JsObject("kind" -> "nodejs".toJson, "code" -> "js1".toJson, "binary" -> false.toJson),
            JsObject("kind" -> "nodejs".toJson, "code" -> "js2".toJson, "binary" -> false.toJson, "foo" -> "bar".toJson),
            JsObject("kind" -> "swift".toJson, "code" -> "swift1".toJson, "binary" -> false.toJson),
            JsObject("kind" -> "nodejs".toJson, "code" -> b64Body.toJson, "binary" -> true.toJson))

        val execs = json.map { e => Exec.serdes.read(e) }

        assert(execs(0) == Exec.js("js1") && json(0).compactPrint == Exec.js("js1").toString)
        assert(execs(1) == Exec.js("js2") && json(1).compactPrint != Exec.js("js2").toString) // ignores unknown properties
        assert(execs(2) == Exec.swift("swift1") && json(2).compactPrint == Exec.swift("swift1").toString)
        assert(execs(3) == Exec.js(b64Body) && json(3).compactPrint == Exec.js(b64Body).toString)
    }

    it should "properly deserialize and reserialize JSON blackbox" in {
        val b64 = Base64.getEncoder()
        val contents = b64.encodeToString("tarball".getBytes)
        val json = Seq[JsObject](
            JsObject("kind" -> "blackbox".toJson, "image" -> "container1".toJson, "binary" -> false.toJson),
            JsObject("kind" -> "blackbox".toJson, "image" -> "container1".toJson, "binary" -> true.toJson, "code" -> contents.toJson))

        val execs = json.map { e => Exec.serdes.read(e) }

        assert(execs(0) == Exec.bb("container1") && json(0).compactPrint == Exec.bb("container1").toString)
        assert(execs(1) == Exec.bb("container1", contents) && json(1).compactPrint == Exec.bb("container1", contents).toString)
        assert(execs(0) == Exec.serdes.read(JsObject("kind" -> "blackbox".toJson, "image" -> "container1".toJson, "binary" -> false.toJson, "code" -> " ".toJson)))
        assert(execs(0) == Exec.serdes.read(JsObject("kind" -> "blackbox".toJson, "image" -> "container1".toJson, "binary" -> false.toJson, "code" -> "".toJson)))
    }

    it should "reject malformed JSON" in {
        val b64 = Base64.getEncoder()
        val contents = b64.encodeToString("tarball".getBytes)

        val execs = Seq[JsValue](
            null,
            JsObject(),
            JsNull,
            JsObject("init" -> "zipfile".toJson),
            JsObject("kind" -> "nodejs".toJson, "code" -> JsNumber(42)),
            JsObject("kind" -> "nodejs".toJson, "init" -> "zipfile".toJson),
            JsObject("kind" -> "turbopascal".toJson, "code" -> "BEGIN1".toJson),
            JsObject("kind" -> "blackbox".toJson, "code" -> "js".toJson),
            JsObject("kind" -> "swift".toJson, "swiftcode" -> "swift".toJson))

        execs.foreach { e =>
            withClue(if (e != null) e else "null") {
                val thrown = intercept[Throwable] {
                    Exec.serdes.read(e)
                }
                thrown match {
                    case _: DeserializationException =>
                    case _: IllegalArgumentException =>
                    case t                           => assert(false, "Unexpected exception:" + t)
                }
            }
        }
    }

    it should "reject null code/image arguments" in {
        an[IllegalArgumentException] should be thrownBy Exec.serdes.read(null)
        a[DeserializationException] should be thrownBy Exec.serdes.read("{}" parseJson)
        a[DeserializationException] should be thrownBy Exec.serdes.read(JsString(""))
    }

    it should "serialize to json" in {
        val execs = Seq(Exec.bb("container"), Exec.js("js"), Exec.js("js"), Exec.swift("swft")).map { _.toString }
        assert(execs(0) == JsObject("kind" -> "blackbox".toJson, "image" -> "container".toJson, "binary" -> false.toJson).compactPrint)
        assert(execs(1) == JsObject("kind" -> "nodejs".toJson, "code" -> "js".toJson, "binary" -> false.toJson).compactPrint)
        assert(execs(2) == JsObject("kind" -> "nodejs".toJson, "code" -> "js".toJson, "binary" -> false.toJson).compactPrint)
        assert(execs(3) == JsObject("kind" -> "swift".toJson, "code" -> "swft".toJson, "binary" -> false.toJson).compactPrint)
    }

    behavior of "Parameter"
    it should "properly deserialize and reserialize JSON" in {
        val json = Seq[JsValue](
            JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson)),
            JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson, "foo" -> "bar".toJson)),
            JsArray(JsObject("key" -> "k".toJson, "value" -> 3.toJson)),
            JsArray(JsObject("key" -> "k".toJson, "value" -> Vector(false, true).toJson)))
        val params = json.map { p => Parameters.serdes.read(p) }
        assert(params(0) == Parameters("k", "v"))
        assert(params(1) == Parameters("k", "v"))
        assert(params(0).toString == json(0).compactPrint)
        assert(params(1).toString == json(0).compactPrint) // drops unknown prop "foo"
        assert(params(1).toString != json(1).compactPrint) // drops unknown prop "foo"
        assert(params(2).toString == json(2).compactPrint) // drops unknown prop "foo"
        assert(params(3).toString == json(3).compactPrint) // drops unknown prop "foo"
    }

    it should "filter immutable parameters" in {
        val params = Parameters("k", "v") ++ Parameters("ns", null: String) ++ Parameters("njs", JsNull)
        params.immutableParameters shouldBe Set("k")
    }

    it should "reject malformed JSON" in {
        val params = Seq[JsValue](
            null,
            JsObject(),
            JsObject("key" -> "k".toJson),
            JsObject("value" -> "v".toJson),
            JsObject("key" -> JsNull, "value" -> "v".toJson),
            JsObject("key" -> "k".toJson, ("value" -> JsNull)),
            JsObject("key" -> JsNull, "value" -> JsNull),
            JsObject("KEY" -> "k".toJson, "VALUE" -> "v".toJson),
            JsObject("key" -> "k".toJson, "value" -> 0.toJson))

        params.foreach {
            p => a[DeserializationException] should be thrownBy Parameters.serdes.read(p)
        }
    }

    it should "reject undefined key" in {
        a[DeserializationException] should be thrownBy Parameters.serdes.read(null: JsValue)
        an[IllegalArgumentException] should be thrownBy Parameters(null, null: String)
        an[IllegalArgumentException] should be thrownBy Parameters("", null: JsValue)
        an[IllegalArgumentException] should be thrownBy Parameters(" ", null: String)
        an[IllegalArgumentException] should be thrownBy Parameters(null, "")
        an[IllegalArgumentException] should be thrownBy Parameters(null, " ")
        an[IllegalArgumentException] should be thrownBy Parameters(null)

    }

    it should "serialize to json" in {
        assert(Parameters("k", null: String).toString == JsArray(JsObject("key" -> "k".toJson, "value" -> JsNull)).compactPrint)
        assert(Parameters("k", "").toString == JsArray(JsObject("key" -> "k".toJson, "value" -> "".toJson)).compactPrint)
        assert(Parameters("k", " ").toString == JsArray(JsObject("key" -> "k".toJson, "value" -> "".toJson)).compactPrint)
        assert(Parameters("k", "v").toString == JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson)).compactPrint)
    }

    behavior of "ActionLimits"

    it should "properly deserialize JSON" in {
        val json = Seq[JsValue](
            JsObject("timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson, "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson, "logs" -> LogLimit.STD_LOGSIZE.toMB.toInt.toJson),
            JsObject("timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson, "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson, "logs" -> LogLimit.STD_LOGSIZE.toMB.toInt.toJson, "foo" -> "bar".toJson),
            JsObject("timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson, "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson))
        val limits = json.map(ActionLimits.serdes.read)
        assert(limits(0) == ActionLimits())
        assert(limits(1) == ActionLimits())
        assert(limits(2) == ActionLimits())
        assert(limits(0).toJson == json(0))
        assert(limits(1).toJson == json(0)) // drops unknown prop "foo"
        assert(limits(1).toJson != json(1)) // drops unknown prop "foo"
    }

    it should "reject malformed JSON" in {
        val limits = Seq[JsValue](
            null,
            JsObject(),
            JsNull,
            JsObject("timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson),
            JsObject("memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson),
            JsObject("logs" -> (LogLimit.STD_LOGSIZE.toMB.toInt + 1).toJson),
            JsObject("TIMEOUT" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson, "MEMORY" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson),
            JsObject("timeout" -> (TimeLimit.STD_DURATION.toMillis.toDouble + .01).toJson, "memory" -> (MemoryLimit.STD_MEMORY.toMB.toDouble + .01).toJson),
            JsObject("timeout" -> null, "memory" -> null),
            JsObject("timeout" -> JsNull, "memory" -> JsNull),
            JsObject("timeout" -> TimeLimit.STD_DURATION.toMillis.toString.toJson, "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toString.toJson))

        limits.foreach {
            p => a[DeserializationException] should be thrownBy ActionLimits.serdes.read(p)
        }
    }

    it should "pass the correct error message through" in {
        val serdes = Seq(TimeLimit.serdes, MemoryLimit.serdes, LogLimit.serdes)

        serdes foreach { s =>
            withClue(s"serializer $s") {
                if (s != LogLimit.serdes) {
                    val lb = the[DeserializationException] thrownBy s.read(JsNumber(0))
                    lb.getMessage should include("below allowed threshold")
                } else {
                    val lb = the[DeserializationException] thrownBy s.read(JsNumber(-1))
                    lb.getMessage should include("a negative size of an object is not allowed")
                }

                val ub = the[DeserializationException] thrownBy s.read(JsNumber(Int.MaxValue))
                ub.getMessage should include("exceeds allowed threshold")

                val int = the[DeserializationException] thrownBy s.read(JsNumber(2.5))
                int.getMessage should include("limit must be whole number")
            }
        }
    }

    it should "reject bad limit values" in {
        an[IllegalArgumentException] should be thrownBy ActionLimits(TimeLimit(TimeLimit.MIN_DURATION - 1.millisecond), MemoryLimit(), LogLimit())
        an[IllegalArgumentException] should be thrownBy ActionLimits(TimeLimit(), MemoryLimit(MemoryLimit.MIN_MEMORY - 1.B), LogLimit())
        an[IllegalArgumentException] should be thrownBy ActionLimits(TimeLimit(), MemoryLimit(), LogLimit(LogLimit.MIN_LOGSIZE - 1.B))

        an[IllegalArgumentException] should be thrownBy ActionLimits(TimeLimit(TimeLimit.MAX_DURATION + 1.millisecond), MemoryLimit(), LogLimit())
        an[IllegalArgumentException] should be thrownBy ActionLimits(TimeLimit(), MemoryLimit(MemoryLimit.MAX_MEMORY + 1.B), LogLimit())
        an[IllegalArgumentException] should be thrownBy ActionLimits(TimeLimit(), MemoryLimit(), LogLimit(LogLimit.MAX_LOGSIZE + 1.B))
    }

    it should "parse activation id as uuid" in {
        val id = "213174381920559471141441e1111111"
        val aid = ActivationId.unapply(id)
        assert(aid.isDefined)
        assert(aid.get.toString == id)
    }

    it should "parse activation id as uuid when made up of no numbers" in {
        val id = "a" * 32
        val aid = ActivationId.unapply(id)
        assert(aid.isDefined)
        assert(aid.get.toString == id)
    }

    it should "parse activation id as uuid when made up of no letters" in {
        val id = "1" * 32
        val aid = ActivationId.unapply(id)
        assert(aid.isDefined)
        assert(aid.get.toString == id)
    }

    it should "parse an activation id as uuid when it is a number" in {
        val id = "1" * 32
        val aid = Try { ActivationId.serdes.read(BigInt(id).toJson) }
        assert(aid.isSuccess)
        assert(aid.get.toString == id)
    }

    it should "not parse invalid activation id" in {
        val id = "213174381920559471141441e111111z"
        assert(ActivationId.unapply(id).isEmpty)
        assert(Try { ActivationId.serdes.read(JsString(id)) }.failed.get.getMessage.contains("malformed"))
    }

    it should "not parse activation id if longer than uuid" in {
        val id = "213174381920559471141441e1111111abc"
        assert(ActivationId.unapply(id).isEmpty)
        assert(Try { ActivationId.serdes.read(JsString(id)) }.failed.get.getMessage.contains("too long"))
    }

    it should "not parse activation id if shorter than uuid" in {
        val id = "213174381920559471141441e1"
        assert(ActivationId.unapply(id).isEmpty)
        assert(Try { ActivationId.serdes.read(JsString(id)) }.failed.get.getMessage.contains("too short"))
    }
}
