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

import spray.json.DefaultJsonProtocol._
import spray.json.DeserializationException
import spray.json.JsArray
import spray.json.JsNull
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.pimpAny
import spray.json.pimpString

import whisk.core.entity.ActionLimits
import whisk.core.entity.ActivationId
import whisk.core.entity.AuthKey
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.LogLimit
import whisk.core.entity.MemoryLimit
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.Secret
import whisk.core.entity.SemVer
import whisk.core.entity.TimeLimit
import whisk.core.entity.UUID
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
        Seq(null, "", " ", ":", " : ", " :", ": ", "a:b").foreach { i =>
            intercept[IllegalArgumentException] {
                AuthKey(i)
            }
        }
    }

    behavior of "DocInfo"

    it should "accept well formed doc info" in {
        Seq("a", " a", "a ").foreach { i =>
            val d = DocInfo(i)
            assert(d.id() == i.trim)
        }
    }

    it should "reject malformed doc info" in {
        Seq(null, "", " ").foreach { i =>
            intercept[IllegalArgumentException] {
                DocInfo(i)
            }
        }
    }

    it should "reject malformed doc ids" in {
        Seq(null, "", " ").foreach { i =>
            intercept[IllegalArgumentException] {
                DocId(i)
            }
        }
    }

    behavior of "Namespace"

    it should "accept well formed paths" in {
        val paths = Seq("/a", "//a", "//a//", "//a//b//c", "//a//b/c//", "a", "a/b", "a/b/", "a@b.c", "a@b.c/", "a@b.c/d", "_a/", "_ _", "a/b/c")
        val expected = Seq("a", "a", "a", "a/b/c", "a/b/c", "a", "a/b", "a/b", "a@b.c", "a@b.c", "a@b.c/d", "_a", "_ _", "a/b/c")
        val spaces = paths.zip(expected).foreach { p =>
            assert(Namespace(p._1).namespace == p._2)
        }
    }

    it should "reject malformed paths" in {
        val paths = Seq(null, "", " ", "a/ ", "a/b/c ", " xxx", "xxx ", " xxx", "xxx/ ", "/", " /", "/ ", "//", "///", " / / / ", "a/b/ c", "a/ /b", " a/ b")
        paths.foreach { p =>
            val thrown = intercept[IllegalArgumentException] {
                Namespace(p)
            }
        }
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
        paths.foreach { p =>
            val thrown = intercept[IllegalArgumentException] {
                EntityName(p)
            }
        }
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
        intercept[IllegalArgumentException] {
            SemVer(-1, 0, 0)
        }
        intercept[IllegalArgumentException] {
            SemVer(0, -1, 0)
        }
        intercept[IllegalArgumentException] {
            SemVer(0, 0, -1)
        }
        intercept[IllegalArgumentException] {
            SemVer(0, 0, 0)
        }
    }

    behavior of "Exec"

    it should "properly deserialize and reserialize JSON" in {
        val json = Seq[JsObject](
            JsObject("kind" -> "nodejs".toJson, "code" -> "js1".toJson),
            JsObject("kind" -> "nodejs".toJson, "code" -> "js2".toJson, "init" -> "zipfile2".toJson),
            JsObject("kind" -> "nodejs".toJson, "code" -> "js3".toJson, "init" -> "zipfile3".toJson, "foo" -> "bar".toJson),
            JsObject("kind" -> "blackbox".toJson, "image" -> "container1".toJson),
            JsObject("kind" -> "swift".toJson, "code" -> "swift1".toJson))

        val execs = json.map { e => Exec.serdes.read(e) }

        assert(execs(0) == Exec.js("js1") && json(0).compactPrint == Exec.js("js1").toString)
        assert(execs(1) == Exec.js("js2", "zipfile2") && json(1).compactPrint == Exec.js("js2", "zipfile2").toString)
        assert(execs(2) == Exec.js("js3", "zipfile3") && json(2).compactPrint != Exec.js("js3", "zipfile3").toString) // ignores unknown properties
        assert(execs(3) == Exec.bb("container1") && json(3).compactPrint == Exec.bb("container1").toString)
        assert(execs(4) == Exec.swift("swift1") && json(4).compactPrint == Exec.swift("swift1").toString)

    }

    it should "reject malformed JSON" in {
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

    it should "reject null code/image arguments" in {
        intercept[IllegalArgumentException] {
            Exec.serdes.read(null)
        }
        intercept[DeserializationException] {
            Exec.serdes.read("{}" parseJson)
        }
        intercept[DeserializationException] {
            Exec.serdes.read(JsString(""))
        }
    }

    it should "serialize to json" in {
        val execs = Seq(Exec.bb("container"), Exec.js("js"), Exec.js("js", "zipfile"), Exec.swift("swft")).map { _.toString }
        assert(execs(0) == JsObject("kind" -> "blackbox".toJson, "image" -> "container".toJson).compactPrint)
        assert(execs(1) == JsObject("kind" -> "nodejs".toJson, "code" -> "js".toJson).compactPrint)
        assert(execs(2) == JsObject("kind" -> "nodejs".toJson, "code" -> "js".toJson, "init" -> "zipfile".toJson).compactPrint)
        assert(execs(3) == JsObject("kind" -> "swift".toJson, "code" -> "swft".toJson).compactPrint)
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

        params.foreach { p =>
            val thrown = intercept[DeserializationException] {
                Parameters.serdes.read(p)
            }
        }
    }

    it should "reject undefined key" in {
        intercept[DeserializationException] {
            Parameters.serdes.read(null: JsValue)
        }
        intercept[IllegalArgumentException] {
            Parameters(null, null: String)
        }
        intercept[IllegalArgumentException] {
            Parameters("", null: JsValue)
        }
        intercept[IllegalArgumentException] {
            Parameters(" ", null: String)
        }
        intercept[IllegalArgumentException] {
            Parameters(null, "")
        }
        intercept[IllegalArgumentException] {
            Parameters(null, " ")
        }
        intercept[IllegalArgumentException] {
            Parameters(null)
        }
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

        limits.foreach { p =>
            val thrown = intercept[DeserializationException] {
                ActionLimits.serdes.read(p)
            }
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
