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

import java.util.Base64

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Try
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.controller.test.WhiskAuthHelpers
import org.apache.openwhisk.core.entitlement.Privilege
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.utils.JsHelpers

@RunWith(classOf[JUnitRunner])
class SchemaTests extends FlatSpec with BeforeAndAfter with ExecHelpers with Matchers {

  behavior of "Privilege"

  private implicit class ExecJson(e: Exec) {
    def asJson: JsObject = Exec.serdes.write(e).asJsObject
  }

  it should "serdes a right" in {
    Privilege.serdes.read("READ".toJson) shouldBe Privilege.READ
    Privilege.serdes.read("read".toJson) shouldBe Privilege.READ
    a[DeserializationException] should be thrownBy Privilege.serdes.read("???".toJson)
  }

  behavior of "TransactionId"

  it should "serdes a transaction id without extraLogging parameter" in {
    val txIdWithoutParameter = TransactionId("4711")

    // test serialization
    val serializedTxIdWithoutParameter = TransactionId.serdes.write(txIdWithoutParameter)
    serializedTxIdWithoutParameter match {
      case JsArray(Vector(JsString(id), JsNumber(_))) =>
        assert(id == txIdWithoutParameter.meta.id)
      case _ => withClue(serializedTxIdWithoutParameter) { assert(false) }
    }

    // test deserialization
    val deserializedTxIdWithoutParameter = TransactionId.serdes.read(serializedTxIdWithoutParameter)
    deserializedTxIdWithoutParameter.meta.id should equal(txIdWithoutParameter.meta.id)
    deserializedTxIdWithoutParameter.meta.extraLogging should equal(false)
  }

  it should "serdes a transaction id with extraLogging parameter" in {
    val txIdWithParameter = TransactionId("4711", true)

    // test serialization
    val serializedTxIdWithParameter = TransactionId.serdes.write(txIdWithParameter)
    serializedTxIdWithParameter match {
      case JsArray(Vector(JsString(id), JsNumber(_), JsBoolean(extraLogging))) =>
        assert(id == txIdWithParameter.meta.id)
        assert(extraLogging)
      case _ => withClue(serializedTxIdWithParameter) { assert(false) }
    }

    // test deserialization
    val deserializedTxIdWithParameter = TransactionId.serdes.read(serializedTxIdWithParameter)
    deserializedTxIdWithParameter.meta.id should equal(txIdWithParameter.meta.id)
    assert(deserializedTxIdWithParameter.meta.extraLogging)
  }

  behavior of "Identity"

  it should "serdes write an identity" in {
    val i = WhiskAuthHelpers.newIdentity()
    val expected = JsObject(
      "subject" -> i.subject.asString.toJson,
      "namespace" -> i.namespace.toJson,
      "authkey" -> i.authkey.toEnvironment,
      "rights" -> Array("READ", "PUT", "DELETE", "ACTIVATE").toJson,
      "limits" -> JsObject.empty)
    Identity.serdes.write(i) shouldBe expected
  }

  it should "serdes read a generic identity" in {
    val uuid = UUID()
    val subject = Subject("test_subject")
    val entity = EntityName("test_subject")
    val genericAuthKey = new GenericAuthKey(JsObject("test_key" -> "test_value".toJson))
    val i = WhiskAuthHelpers.newIdentityGenricAuth(subject, uuid, genericAuthKey)

    val json = JsObject(
      "subject" -> Subject("test_subject").toJson,
      "namespace" -> Namespace(entity, uuid).toJson,
      "authkey" -> JsObject("test_key" -> "test_value".toJson),
      "rights" -> Array("READ", "PUT", "DELETE", "ACTIVATE").toJson,
      "limits" -> JsObject.empty)
    Identity.serdes.read(json) shouldBe i
  }

  it should "deserialize view result" in {
    implicit val tid = TransactionId("test")
    val subject = Subject("test_subject")
    val id = WhiskAuthHelpers.newIdentity(subject)

    val json = JsObject(
      "id" -> subject.asString.toJson,
      "value" -> JsObject(
        "uuid" -> id.authkey.asInstanceOf[BasicAuthenticationAuthKey].uuid.toJson,
        "key" -> id.authkey.asInstanceOf[BasicAuthenticationAuthKey].key.toJson,
        "namespace" -> "test_subject".toJson),
      "doc" -> JsNull)

    Identity.rowToIdentity(json, "test") shouldBe id
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

    DocRevision.serdes.read(JsNull) shouldBe DocRevision.empty
    DocRevision.serdes.read(JsString.empty) shouldBe DocRevision("")
    DocRevision.serdes.read(JsString("a")) shouldBe DocRevision("a")
    DocRevision.serdes.read(JsString(" a")) shouldBe DocRevision("a")
    DocRevision.serdes.read(JsString("a ")) shouldBe DocRevision("a")
    a[DeserializationException] should be thrownBy DocRevision.serdes.read(JsNumber(1))
  }

  it should "reject malformed doc info" in {
    Seq(null, "", " ").foreach { i =>
      an[IllegalArgumentException] should be thrownBy DocInfo(i)
    }
  }

  it should "reject malformed doc ids" in {
    Seq(null, "", " ").foreach { i =>
      an[IllegalArgumentException] should be thrownBy DocId(i)
    }
  }

  behavior of "EntityPath"

  it should "accept well formed paths" in {
    val paths = Seq(
      "/a",
      "//a",
      "//a//",
      "//a//b//c",
      "//a//b/c//",
      "a",
      "a/b",
      "a/b/",
      "a@b.c",
      "a@b.c/",
      "a@b.c/d",
      "_a/",
      "_ _",
      "a/b/c")
    val expected =
      Seq("a", "a", "a", "a/b/c", "a/b/c", "a", "a/b", "a/b", "a@b.c", "a@b.c", "a@b.c/d", "_a", "_ _", "a/b/c")
    val spaces = paths.zip(expected).foreach { p =>
      EntityPath(p._1).namespace shouldBe p._2
    }

    EntityPath.DEFAULT.addPath(EntityName("a")).toString shouldBe "_/a"

    EntityPath.DEFAULT.addPath(EntityPath("a")).toString shouldBe "_/a"
    EntityPath.DEFAULT.addPath(EntityPath("a/b")).toString shouldBe "_/a/b"

    EntityPath.DEFAULT.resolveNamespace(EntityName("a")) shouldBe EntityPath("a")
    EntityPath("a").resolveNamespace(EntityName("b")) shouldBe EntityPath("a")

    EntityPath.DEFAULT.resolveNamespace(Namespace(EntityName("a"), UUID())) shouldBe EntityPath("a")
    EntityPath("a").resolveNamespace(Namespace(EntityName("b"), UUID())) shouldBe EntityPath("a")

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
    val paths = Seq(
      null,
      "",
      " ",
      "a/ ",
      "a/b/c ",
      " xxx",
      "xxx ",
      " xxx",
      "xxx/ ",
      "/",
      " /",
      "/ ",
      "//",
      "///",
      " / / / ",
      "a/b/ c",
      "a/ /b",
      " a/ b")
    paths.foreach { p =>
      an[IllegalArgumentException] should be thrownBy EntityPath(p)
    }

    an[IllegalArgumentException] should be thrownBy EntityPath("a").toFullyQualifiedEntityName
  }

  behavior of "EntityName"

  it should "accept well formed names" in {
    val paths = Seq(
      "a",
      "a b",
      "a@b.c&d",
      "a@&b",
      "_a",
      "_",
      "_ _",
      "a0",
      "a 0",
      "a.0",
      "a@@&",
      "0",
      "0.0",
      "0.0.0",
      "0a",
      "0.a",
      "a" * EntityName.ENTITY_NAME_MAX_LENGTH)
    paths.foreach { n =>
      assert(EntityName(n).toString == n)
    }
  }

  it should "reject malformed names" in {
    val paths = Seq(
      null,
      "",
      " ",
      " xxx",
      "xxx ",
      "/",
      " /",
      "/ ",
      "0 ",
      "a=2b",
      "_ ",
      "a?b",
      "x#x",
      "a§b",
      "a  ",
      "a()b",
      "a{}b",
      "a \t",
      "-abc",
      "&abc",
      "a\n",
      "a" * (EntityName.ENTITY_NAME_MAX_LENGTH + 1))
    paths.foreach { p =>
      an[IllegalArgumentException] should be thrownBy EntityName(p)
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
    FullyQualifiedEntityName.serdes.read(names(1)) shouldBe FullyQualifiedEntityName(
      EntityPath("a"),
      EntityName("b"),
      Some(SemVer()))
    FullyQualifiedEntityName.serdes.read(names(2)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
    FullyQualifiedEntityName.serdes.read(names(3)) shouldBe FullyQualifiedEntityName(EntityPath("n/a"), EntityName("b"))
    FullyQualifiedEntityName.serdes.read(names(4)) shouldBe FullyQualifiedEntityName(EntityPath("a"), EntityName("b"))
    FullyQualifiedEntityName.serdes.read(names(5)) shouldBe FullyQualifiedEntityName(EntityPath("n/a"), EntityName("b"))
    a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdes.read(names(6))

    a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdesAsDocId.read(names(0))
    a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdesAsDocId.read(names(1))
    FullyQualifiedEntityName.serdesAsDocId.read(names(2)) shouldBe FullyQualifiedEntityName(
      EntityPath("a"),
      EntityName("b"))
    FullyQualifiedEntityName.serdesAsDocId.read(names(3)) shouldBe FullyQualifiedEntityName(
      EntityPath("n/a"),
      EntityName("b"))
    FullyQualifiedEntityName.serdesAsDocId.read(names(4)) shouldBe FullyQualifiedEntityName(
      EntityPath("a"),
      EntityName("b"))
    FullyQualifiedEntityName.serdesAsDocId.read(names(5)) shouldBe FullyQualifiedEntityName(
      EntityPath("n/a"),
      EntityName("b"))
    a[DeserializationException] should be thrownBy FullyQualifiedEntityName.serdesAsDocId.read(names(6))
  }

  it should "resolve names that may or may not be fully qualified" in {
    FullyQualifiedEntityName.resolveName(JsString("a"), EntityName("ns")) shouldBe Some(
      EntityPath("ns/a").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("/_/a"), EntityName("ns")) shouldBe Some(
      EntityPath("ns/a").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("_/a"), EntityName("ns")) shouldBe Some(
      EntityPath("ns/_/a").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("/_/a/b"), EntityName("ns")) shouldBe Some(
      EntityPath("ns/a/b").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("a/b"), EntityName("ns")) shouldBe Some(
      EntityPath("ns/a/b").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("a/b/c"), EntityName("ns")) shouldBe Some(
      EntityPath("/a/b/c").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("a/b/c/d"), EntityName("ns")) shouldBe None

    FullyQualifiedEntityName.resolveName(JsString("/a"), EntityName("ns")) shouldBe None

    FullyQualifiedEntityName.resolveName(JsString("/a/b"), EntityName("ns")) shouldBe Some(
      EntityPath("/a/b").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("/a/b/c"), EntityName("ns")) shouldBe Some(
      EntityPath("/a/b/c").toFullyQualifiedEntityName)

    FullyQualifiedEntityName.resolveName(JsString("/a/b/c/d"), EntityName("ns")) shouldBe None

    FullyQualifiedEntityName.resolveName(JsString.empty, EntityName("ns")) shouldBe None
  }

  behavior of "Binding"

  it should "desiarilize legacy format" in {
    val names =
      Seq(
        JsObject("namespace" -> "a".toJson, "name" -> "b".toJson),
        JsObject.empty,
        JsObject("name" -> "b".toJson),
        JsNull)

    Binding.optionalBindingDeserializer.read(names(0)) shouldBe Some(Binding(EntityName("a"), EntityName("b")))
    Binding.optionalBindingDeserializer.read(names(1)) shouldBe None
    a[DeserializationException] should be thrownBy Binding.optionalBindingDeserializer.read(names(2))
    a[DeserializationException] should be thrownBy Binding.optionalBindingDeserializer.read(names(3))
  }

  it should "serialize optional binding to empty object" in {
    Binding.optionalBindingSerializer.write(None) shouldBe JsObject.empty
  }

  behavior of "WhiskPackagePut"

  it should "deserialize empty request" in {
    WhiskPackagePut.serdes.read(JsObject.empty) shouldBe WhiskPackagePut()
    //WhiskPackagePut.serdes.read(JsObject("binding" -> JsNull)) shouldBe WhiskPackagePut()
    WhiskPackagePut.serdes.read(JsObject("binding" -> JsObject.empty)) shouldBe WhiskPackagePut()
    //WhiskPackagePut.serdes.read(JsObject("binding" -> "a/b".toJson)) shouldBe WhiskPackagePut(binding = Some(Binding(EntityPath("a"), EntityName("b"))))
    a[DeserializationException] should be thrownBy WhiskPackagePut.serdes.read(JsObject("binding" -> JsNull))
  }

  behavior of "WhiskPackage"

  it should "not deserialize package without binding property" in {
    val pkg = WhiskPackage(EntityPath("a"), EntityName("b"))
    WhiskPackage.serdes.read(JsObject(pkg.toJson.fields + ("binding" -> JsObject.empty))) shouldBe pkg
    a[DeserializationException] should be thrownBy WhiskPackage.serdes.read(JsObject(pkg.toJson.fields - "binding"))
  }

  it should "serialize package with empty binding property" in {
    val pkg = WhiskPackage(EntityPath("a"), EntityName("b"))
    WhiskPackage.serdes.write(pkg) shouldBe JsObject(
      "namespace" -> "a".toJson,
      "name" -> "b".toJson,
      "binding" -> JsObject.empty,
      "parameters" -> Parameters().toJson,
      "version" -> SemVer().toJson,
      "publish" -> JsFalse,
      "annotations" -> Parameters().toJson,
      "updated" -> pkg.updated.toEpochMilli.toJson)
  }

  it should "serialize and deserialize package binding" in {
    val pkg = WhiskPackage(EntityPath("a"), EntityName("b"), Some(Binding(EntityName("x"), EntityName("y"))))
    val pkgAsJson = JsObject(
      "namespace" -> "a".toJson,
      "name" -> "b".toJson,
      "binding" -> JsObject("namespace" -> "x".toJson, "name" -> "y".toJson),
      "parameters" -> Parameters().toJson,
      "version" -> SemVer().toJson,
      "publish" -> JsFalse,
      "annotations" -> Parameters().toJson,
      "updated" -> pkg.updated.toEpochMilli.toJson)
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

  it should "initialize exec manifest" in {
    val runtimes = ExecManifest.runtimesManifest
    val kind = runtimes.resolveDefaultRuntime("nodejs:default").get.kind
    Some(kind) should contain oneOf ("nodejs:10", "nodejs:12")
  }

  it should "properly deserialize and reserialize JSON" in {
    val b64Body = """ZnVuY3Rpb24gbWFpbihhcmdzKSB7IHJldHVybiBhcmdzOyB9Cg=="""

    val json = Seq[JsObject](
      JsObject("kind" -> "nodejs:10".toJson, "code" -> "js1".toJson, "binary" -> false.toJson),
      JsObject("kind" -> "nodejs:10".toJson, "code" -> "js2".toJson, "binary" -> false.toJson, "foo" -> "bar".toJson),
      JsObject("kind" -> "swift:4.2".toJson, "code" -> "swift1".toJson, "binary" -> false.toJson),
      JsObject("kind" -> "swift:4.2".toJson, "code" -> b64Body.toJson, "binary" -> true.toJson),
      JsObject("kind" -> "nodejs:10".toJson, "code" -> b64Body.toJson, "binary" -> true.toJson))

    val execs = json.map { e =>
      Exec.serdes.read(e)
    }

    assert(execs(0) == jsDefault("js1") && json(0) == jsDefault("js1").asJson)
    assert(execs(1) == jsDefault("js2") && json(1) != jsDefault("js2").asJson) // ignores unknown properties
    assert(execs(2) == swift("swift1") && json(2) == swift("swift1").asJson)
    assert(execs(3) == swift(b64Body) && json(3) == swift(b64Body).asJson)
    assert(execs(4) == jsDefault(b64Body) && json(4) == jsDefault(b64Body).asJson)
  }

  it should "properly deserialize and reserialize JSON blackbox" in {
    val b64 = Base64.getEncoder()
    val contents = b64.encodeToString("tarball".getBytes)
    val json = Seq[JsObject](
      JsObject("kind" -> "blackbox".toJson, "image" -> "container1".toJson, "binary" -> false.toJson),
      JsObject(
        "kind" -> "blackbox".toJson,
        "image" -> "container1".toJson,
        "binary" -> true.toJson,
        "code" -> contents.toJson),
      JsObject(
        "kind" -> "blackbox".toJson,
        "image" -> "container1".toJson,
        "binary" -> true.toJson,
        "code" -> contents.toJson,
        "main" -> "naim".toJson))

    val execs = json.map { e =>
      Exec.serdes.read(e)
    }

    execs(0) shouldBe bb("container1")
    execs(1) shouldBe bb("container1", contents)
    execs(2) shouldBe bb("container1", contents, Some("naim"))

    json(0) shouldBe bb("container1").asJson
    json(1) shouldBe bb("container1", contents).asJson
    json(2) shouldBe bb("container1", contents, Some("naim")).asJson

    execs(0) shouldBe Exec.serdes.read(
      JsObject(
        "kind" -> "blackbox".toJson,
        "image" -> "container1".toJson,
        "binary" -> false.toJson,
        "code" -> " ".toJson))
    execs(0) shouldBe Exec.serdes.read(
      JsObject(
        "kind" -> "blackbox".toJson,
        "image" -> "container1".toJson,
        "binary" -> false.toJson,
        "code" -> "".toJson))
  }

  it should "exclude undefined code in whisk action initializer" in {
    ExecutableWhiskAction(EntityPath("a"), EntityName("b"), bb("container1")).containerInitializer() shouldBe {
      JsObject("name" -> "b".toJson, "binary" -> false.toJson, "main" -> "main".toJson)
    }
    ExecutableWhiskAction(EntityPath("a"), EntityName("b"), bb("container1", "xyz")).containerInitializer() shouldBe {
      JsObject("name" -> "b".toJson, "binary" -> false.toJson, "main" -> "main".toJson, "code" -> "xyz".toJson)
    }
    ExecutableWhiskAction(EntityPath("a"), EntityName("b"), bb("container1", "", Some("naim")))
      .containerInitializer() shouldBe {
      JsObject("name" -> "b".toJson, "binary" -> false.toJson, "main" -> "naim".toJson)
    }
  }

  it should "allow of main override in action initializer" in {
    ExecutableWhiskAction(EntityPath("a"), EntityName("b"), jsDefault("")).containerInitializer() shouldBe {
      JsObject("name" -> "b".toJson, "binary" -> false.toJson, "code" -> JsString.empty, "main" -> "main".toJson)
    }

    ExecutableWhiskAction(EntityPath("a"), EntityName("b"), jsDefault("", Some("bar")))
      .containerInitializer() shouldBe {
      JsObject("name" -> "b".toJson, "binary" -> false.toJson, "code" -> JsString.empty, "main" -> "bar".toJson)
    }
  }

  it should "include optional environment variables" in {
    val env = Map(
      "A" -> "c".toJson,
      "B" -> JsNull,
      "C" -> JsTrue,
      "D" -> JsNumber(3),
      "E" -> JsArray(JsString("a")),
      "F" -> JsObject("a" -> JsFalse))

    ExecutableWhiskAction(EntityPath("a"), EntityName("b"), bb("container1")).containerInitializer(env) shouldBe {
      JsObject(
        "name" -> "b".toJson,
        "binary" -> false.toJson,
        "main" -> "main".toJson,
        "env" -> JsObject(
          "A" -> JsString("c"),
          "B" -> JsString(""),
          "C" -> JsString("true"),
          "D" -> JsString("3"),
          "E" -> JsString("[\"a\"]"),
          "F" -> JsString("{\"a\":false}")))
    }
  }

  it should "compare as equal two actions even if their revision does not match" in {
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val actionA = WhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)
    val actionB = actionA.copy()
    val actionC = actionA.copy()
    actionC.revision(DocRevision("2"))
    actionA shouldBe actionB
    actionA shouldBe actionC
  }

  it should "compare as equal two executable actions even if their revision does not match" in {
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val actionA = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)
    val actionB = actionA.copy()
    val actionC = actionA.copy()
    actionC.revision(DocRevision("2"))
    actionA shouldBe actionB
    actionA shouldBe actionC
  }

  it should "reject malformed JSON" in {
    val b64 = Base64.getEncoder()
    val contents = b64.encodeToString("tarball".getBytes)

    val execs = Seq[JsValue](
      null,
      JsObject.empty,
      JsNull,
      JsObject("init" -> "zipfile".toJson),
      JsObject("kind" -> "nodejs:10".toJson, "code" -> JsNumber(42)),
      JsObject("kind" -> "nodejs:10".toJson, "init" -> "zipfile".toJson),
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
    a[DeserializationException] should be thrownBy Exec.serdes.read(JsString.empty)
  }

  it should "serialize to json" in {
    val execs = Seq(bb("container"), jsDefault("js"), jsDefault("js"), swift("swift")).map { _.asJson }
    assert(execs(0) == JsObject("kind" -> "blackbox".toJson, "image" -> "container".toJson, "binary" -> false.toJson))
    assert(execs(1) == JsObject("kind" -> "nodejs:10".toJson, "code" -> "js".toJson, "binary" -> false.toJson))
    assert(execs(2) == JsObject("kind" -> "nodejs:10".toJson, "code" -> "js".toJson, "binary" -> false.toJson))
    assert(execs(3) == JsObject("kind" -> "swift:4.2".toJson, "code" -> "swift".toJson, "binary" -> false.toJson))
  }

  behavior of "Parameter"

  it should "properly deserialize and reserialize JSON without optional field" in {
    val json = Seq[JsValue](
      JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson)),
      JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson, "foo" -> "bar".toJson)),
      JsArray(JsObject("key" -> "k".toJson, "value" -> 3.toJson)),
      JsArray(JsObject("key" -> "k".toJson, "value" -> Vector(false, true).toJson)))
    val params = json.map { p =>
      Parameters.serdes.read(p)
    }
    assert(params(0) == Parameters("k", "v"))
    assert(params(1) == Parameters("k", "v"))
    assert(params(0).toString == json(0).compactPrint)
    assert(params(1).toString == json(0).compactPrint) // drops unknown prop "foo"
    assert(params(1).toString != json(1).compactPrint) // drops unknown prop "foo"
    assert(params(2).toString == json(2).compactPrint) // drops unknown prop "foo"
    assert(params(3).toString == json(3).compactPrint) // drops unknown prop "foo"
  }

  it should "properly deserialize and reserialize parameters with optional field" in {
    val json = Seq[JsValue](
      JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson)),
      JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson, "init" -> JsFalse)),
      JsArray(JsObject(Map("key" -> "k".toJson, "value" -> "v".toJson, "init" -> JsTrue))))

    val params = json.map { p =>
      Parameters.serdes.read(p)
    }
    assert(params(0) == Parameters("k", "v"))
    assert(params(1) == Parameters("k", "v", false))
    assert(params(2) == Parameters("k", "v", true))
    assert(params(0).toString == json(0).compactPrint)
    assert(params(1).toString == json(0).compactPrint) // init == false drops the property from the JSON
    assert(params(2).toString == json(2).compactPrint)
  }

  it should "reject parameters with invalid optional field" in {
    val json = Seq[JsValue](JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson, "init" -> JsString("true"))))

    json.foreach { p =>
      an[DeserializationException] should be thrownBy Parameters.serdes.read(p)
    }
  }

  it should "filter immutable parameters" in {
    val params = Parameters("k", "v") ++ Parameters("ns", null: String) ++ Parameters("njs", JsNull)
    params.definedParameters shouldBe Set("k")
  }

  it should "reject malformed JSON" in {
    val params = Seq[JsValue](
      null,
      JsObject.empty,
      JsObject("key" -> "k".toJson),
      JsObject("value" -> "v".toJson),
      JsObject("key" -> JsNull, "value" -> "v".toJson),
      JsObject("key" -> "k".toJson, ("value" -> JsNull)),
      JsObject("key" -> JsNull, "value" -> JsNull),
      JsObject("KEY" -> "k".toJson, "VALUE" -> "v".toJson),
      JsObject("key" -> "k".toJson, "value" -> 0.toJson))

    params.foreach { p =>
      a[DeserializationException] should be thrownBy Parameters.serdes.read(p)
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

  it should "recognize truthy values" in {
    Seq(JsTrue, JsNumber(1), JsString("x")).foreach { v =>
      Parameters("x", v).isTruthy("x") shouldBe true
    }

    Seq(JsFalse, JsNumber(0), JsString.empty, JsNull).foreach { v =>
      Parameters("x", v).isTruthy("x") shouldBe false
    }

    Parameters("x", JsTrue).isTruthy("y") shouldBe false
    Parameters("x", JsTrue).isTruthy("y", valueForNonExistent = true) shouldBe true
  }

  it should "serialize to json" in {
    assert(
      Parameters("k", null: String).toString == JsArray(JsObject("key" -> "k".toJson, "value" -> JsNull)).compactPrint)
    assert(Parameters("k", "").toString == JsArray(JsObject("key" -> "k".toJson, "value" -> "".toJson)).compactPrint)
    assert(Parameters("k", " ").toString == JsArray(JsObject("key" -> "k".toJson, "value" -> "".toJson)).compactPrint)
    assert(Parameters("k", "v").toString == JsArray(JsObject("key" -> "k".toJson, "value" -> "v".toJson)).compactPrint)
  }

  behavior of "ActionLimits"

  it should "properly deserialize JSON" in {
    val json = Seq[JsValue](
      JsObject(
        "timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson,
        "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson,
        "logs" -> LogLimit.STD_LOGSIZE.toMB.toInt.toJson,
        "concurrency" -> ConcurrencyLimit.STD_CONCURRENT.toInt.toJson),
      JsObject(
        "timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson,
        "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson,
        "logs" -> LogLimit.STD_LOGSIZE.toMB.toInt.toJson,
        "concurrency" -> ConcurrencyLimit.STD_CONCURRENT.toInt.toJson,
        "foo" -> "bar".toJson),
      JsObject(
        "timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson,
        "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson))
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
      JsObject.empty,
      JsNull,
      JsObject("timeout" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson),
      JsObject("memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson),
      JsObject("logs" -> (LogLimit.STD_LOGSIZE.toMB.toInt + 1).toJson),
      JsObject(
        "TIMEOUT" -> TimeLimit.STD_DURATION.toMillis.toInt.toJson,
        "MEMORY" -> MemoryLimit.STD_MEMORY.toMB.toInt.toJson),
      JsObject(
        "timeout" -> (TimeLimit.STD_DURATION.toMillis.toDouble + .01).toJson,
        "memory" -> (MemoryLimit.STD_MEMORY.toMB.toDouble + .01).toJson),
      JsObject("timeout" -> null, "memory" -> null),
      JsObject("timeout" -> JsNull, "memory" -> JsNull),
      JsObject(
        "timeout" -> TimeLimit.STD_DURATION.toMillis.toString.toJson,
        "memory" -> MemoryLimit.STD_MEMORY.toMB.toInt.toString.toJson))

    limits.foreach { p =>
      a[DeserializationException] should be thrownBy ActionLimits.serdes.read(p)
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
    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(TimeLimit.MIN_DURATION - 1.millisecond),
      MemoryLimit(),
      LogLimit())
    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(),
      MemoryLimit(MemoryLimit.MIN_MEMORY - 1.B),
      LogLimit())
    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(),
      MemoryLimit(),
      LogLimit(LogLimit.MIN_LOGSIZE - 1.B))
    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(),
      MemoryLimit(),
      LogLimit(),
      ConcurrencyLimit(ConcurrencyLimit.MIN_CONCURRENT - 1))

    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(TimeLimit.MAX_DURATION + 1.millisecond),
      MemoryLimit(),
      LogLimit())
    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(),
      MemoryLimit(MemoryLimit.MAX_MEMORY + 1.B),
      LogLimit())
    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(),
      MemoryLimit(),
      LogLimit(LogLimit.MAX_LOGSIZE + 1.B))
    an[IllegalArgumentException] should be thrownBy ActionLimits(
      TimeLimit(),
      MemoryLimit(),
      LogLimit(),
      ConcurrencyLimit(ConcurrencyLimit.MAX_CONCURRENT + 1))
  }

  it should "parse activation id as uuid" in {
    val id = "213174381920559471141441e1111111"
    val aid = ActivationId.parse(id)
    assert(aid.isSuccess)
    assert(aid.get.toString == id)
  }

  it should "parse activation id as uuid when made up of no numbers" in {
    val id = "a" * 32
    val aid = ActivationId.parse(id)
    assert(aid.isSuccess)
    assert(aid.get.toString == id)
  }

  it should "parse activation id as uuid when made up of no letters" in {
    val id = "1" * 32
    val aid = ActivationId.parse(id)
    assert(aid.isSuccess)
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
    assert(ActivationId.parse(id).isFailure)
    Try(ActivationId.serdes.read(JsString(id))) shouldBe Failure {
      DeserializationException(Messages.activationIdIllegal)
    }
  }

  it should "not parse activation id if longer than uuid" in {
    val id = "213174381920559471141441e1111111abc"
    assert(ActivationId.parse(id).isFailure)
    Try(ActivationId.serdes.read(JsString(id))) shouldBe Failure {
      DeserializationException(Messages.activationIdLengthError(SizeError("Activation id", id.length.B, 32.B)))
    }
  }

  it should "not parse activation id if shorter than uuid" in {
    val id = "213174381920559471141441e1"
    ActivationId.parse(id) shouldBe 'failure
    Try(ActivationId.serdes.read(JsString(id))) shouldBe Failure {
      DeserializationException(Messages.activationIdLengthError(SizeError("Activation id", id.length.B, 32.B)))
    }
  }

  behavior of "Js Helpers"

  it should "project paths from json object" in {
    val js = JsObject("a" -> JsObject("b" -> JsObject("c" -> JsString("v"))), "b" -> JsString("v"))
    JsHelpers.fieldPathExists(js) shouldBe true
    JsHelpers.fieldPathExists(js, "a") shouldBe true
    JsHelpers.fieldPathExists(js, "a", "b") shouldBe true
    JsHelpers.fieldPathExists(js, "a", "b", "c") shouldBe true
    JsHelpers.fieldPathExists(js, "a", "b", "c", "d") shouldBe false
    JsHelpers.fieldPathExists(js, "b") shouldBe true
    JsHelpers.fieldPathExists(js, "c") shouldBe false

    JsHelpers.getFieldPath(js) shouldBe Some(js)
    JsHelpers.getFieldPath(js, "x") shouldBe None
    JsHelpers.getFieldPath(js, "b") shouldBe Some(JsString("v"))
    JsHelpers.getFieldPath(js, "a") shouldBe Some(JsObject("b" -> JsObject("c" -> JsString("v"))))
    JsHelpers.getFieldPath(js, "a", "b") shouldBe Some(JsObject("c" -> JsString("v")))
    JsHelpers.getFieldPath(js, "a", "b", "c") shouldBe Some(JsString("v"))
    JsHelpers.getFieldPath(js, "a", "b", "c", "d") shouldBe None
    JsHelpers.getFieldPath(JsObject.empty) shouldBe Some(JsObject.empty)
  }
}
