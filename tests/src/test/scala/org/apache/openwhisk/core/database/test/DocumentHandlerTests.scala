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

package org.apache.openwhisk.core.database.test

import java.util.concurrent.atomic.AtomicInteger

import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers, OptionValues}
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.SubjectHandler.SubjectView
import org.apache.openwhisk.core.database.WhisksHandler.ROOT_NS
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity._

import scala.concurrent.Future

@RunWith(classOf[JUnitRunner])
class DocumentHandlerTests extends FlatSpec with Matchers with ScalaFutures with OptionValues with WskActorSystem {

  val cnt = new AtomicInteger(0)
  def transid() = TransactionId(cnt.incrementAndGet().toString)

  behavior of "WhisksHandler computeFields"

  it should "return empty object when namespace does not exist" in {
    WhisksHandler.computedFields(JsObject.empty) shouldBe JsObject.empty
  }

  it should "return JsObject when namespace is simple name" in {
    WhisksHandler.computedFields(JsObject(("namespace", JsString("foo")))) shouldBe JsObject((ROOT_NS, JsString("foo")))
    WhisksHandler.computedFields(newRule("foo").toDocumentRecord) shouldBe JsObject((ROOT_NS, JsString("foo")))
  }

  it should "return JsObject when namespace is path" in {
    WhisksHandler.computedFields(JsObject(("namespace", JsString("foo/bar")))) shouldBe
      JsObject((ROOT_NS, JsString("foo")))

    WhisksHandler.computedFields(newRule("foo/bar").toDocumentRecord) shouldBe JsObject((ROOT_NS, JsString("foo")))
  }

  private def newRule(ns: String): WhiskRule = {
    WhiskRule(
      EntityPath(ns),
      EntityName("foo"),
      FullyQualifiedEntityName(EntityPath("system"), EntityName("bar")),
      FullyQualifiedEntityName(EntityPath("system"), EntityName("bar")))
  }

  behavior of "WhisksHandler computeView"

  it should "include only common fields in trigger view" in {
    val js = """{
               |  "namespace" : "foo",
               |  "version" : 5,
               |  "end"   : 9,
               |  "cause" : 204
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace" : "foo",
                   |  "version" : 5
                   |}""".stripMargin.parseJson.asJsObject
    WhisksHandler.computeView("foo", "triggers", js) shouldBe result
  }

  it should "include false binding in public package view" in {
    val js =
      """{
        |  "namespace" : "foo",
        |  "version" : 5,
        |  "binding"   : {"foo" : "bar"},
        |  "cause" : 204
        |}""".stripMargin.parseJson.asJsObject

    val result =
      """{
        |  "namespace" : "foo",
        |  "version" : 5,
        |  "binding" : false
        |}""".stripMargin.parseJson.asJsObject
    WhisksHandler.computeView("foo", "packages-public", js) shouldBe result
  }

  it should "include actual binding in package view" in {
    val js = """{
               |  "namespace" : "foo",
               |  "version" : 5,
               |  "binding"   : {"foo" : "bar"},
               |  "cause" : 204
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace" : "foo",
                   |  "version" : 5,
                   |  "binding" : {"foo" : "bar"}
                   |}""".stripMargin.parseJson.asJsObject
    WhisksHandler.computeView("foo", "packages", js) shouldBe result
  }

  it should "include limits and binary info in action view" in {
    val js = """{
               |  "namespace" : "foo",
               |  "version" : 5,
               |  "binding"   : {"foo" : "bar"},
               |  "limits" : 204,
               |  "exec" : {"binary" : true }
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace" : "foo",
                   |  "version" : 5,
                   |  "limits" : 204,
                   |  "exec" : { "binary" : true }
                   |}""".stripMargin.parseJson.asJsObject
    WhisksHandler.computeView("foo", "actions", js) shouldBe result
  }

  it should "include binary as false when exec missing" in {
    val js = """{
               |  "namespace" : "foo",
               |  "version" : 5,
               |  "binding"   : {"foo" : "bar"},
               |  "limits" : 204
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace" : "foo",
                   |  "version" : 5,
                   |  "limits" : 204,
                   |  "exec" : { "binary" : false }
                   |}""".stripMargin.parseJson.asJsObject
    WhisksHandler.computeView("foo", "actions", js) shouldBe result
  }

  it should "include binary as false when exec does not have binary prop" in {
    val js = """{
               |  "namespace" : "foo",
               |  "version" : 5,
               |  "binding"   : {"foo" : "bar"},
               |  "limits" : 204,
               |  "exec" : { "code" : "stuff" }
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace" : "foo",
                   |  "version" : 5,
                   |  "limits" : 204,
                   |  "exec" : { "binary" : false }
                   |}""".stripMargin.parseJson.asJsObject
    WhisksHandler.computeView("foo", "actions", js) shouldBe result
  }

  behavior of "WhisksHandler fieldsRequiredForView"

  it should "match the expected field names" in {
    WhisksHandler.fieldsRequiredForView("foo", "actions") shouldBe
      Set("namespace", "name", "version", "publish", "annotations", "updated", "limits", "exec.binary")

    WhisksHandler.fieldsRequiredForView("foo", "packages") shouldBe
      Set("namespace", "name", "version", "publish", "annotations", "updated", "binding")

    WhisksHandler.fieldsRequiredForView("foo", "packages-public") shouldBe
      Set("namespace", "name", "version", "publish", "annotations", "updated")

    WhisksHandler.fieldsRequiredForView("foo", "rules") shouldBe
      Set("namespace", "name", "version", "publish", "annotations", "updated")

    WhisksHandler.fieldsRequiredForView("foo", "triggers") shouldBe
      Set("namespace", "name", "version", "publish", "annotations", "updated")

    intercept[UnsupportedView] {
      WhisksHandler.fieldsRequiredForView("foo", "unknown") shouldBe Set.empty
    }
  }

  behavior of "ActivationHandler computeFields"

  it should "return default value when no annotation found" in {
    val js = """{"foo" : "bar"}""".parseJson.asJsObject
    ActivationHandler.annotationValue(js, "fooKey", { _.convertTo[String] }, "barValue") shouldBe "barValue"

    val js2 = """{"foo" : "bar", "annotations" : "a"}""".parseJson.asJsObject
    ActivationHandler.annotationValue(js2, "fooKey", { _.convertTo[String] }, "barValue") shouldBe "barValue"

    val js3 = """{"foo" : "bar", "annotations" : [{"key" : "someKey", "value" : "someValue"}]}""".parseJson.asJsObject
    ActivationHandler.annotationValue(js3, "fooKey", { _.convertTo[String] }, "barValue") shouldBe "barValue"
  }

  it should "return transformed value when annotation found" in {
    val js = """{
               |  "foo": "bar",
               |  "annotations": [
               |    {
               |      "key": "fooKey",
               |      "value": "fooValue"
               |    }
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.annotationValue(js, "fooKey", { _.convertTo[String] + "-x" }, "barValue") shouldBe "fooValue-x"
  }

  it should "computeFields with deleteLogs true" in {
    val js = """{
               |  "foo": "bar",
               |  "annotations": [
               |    {
               |      "key": "fooKey",
               |      "value": "fooValue"
               |    }
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computedFields(js) shouldBe """{"deleteLogs" : true}""".parseJson
  }

  it should "computeFields with deleteLogs false for sequence kind" in {
    val js = """{
               |  "foo": "bar",
               |  "annotations": [
               |    {
               |      "key": "kind",
               |      "value": "sequence"
               |    }
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computedFields(js) shouldBe """{"deleteLogs" : false}""".parseJson
  }

  it should "computeFields with nspath as namespace" in {
    val js = """{
               |  "namespace": "foons",
               |  "name":"bar",
               |  "annotations": [
               |    {
               |      "key": "kind",
               |      "value": "action"
               |    }
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computedFields(js) shouldBe """{"nspath": "foons/bar", "deleteLogs" : true}""".parseJson
  }

  it should "computeFields with nspath as qualified path" in {
    val js = """{
               |  "namespace": "foons",
               |  "name":"bar",
               |  "annotations": [
               |    {
               |      "key": "path",
               |      "value": "barns/barpkg/baraction"
               |    }
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computedFields(js) shouldBe """{"nspath": "foons/barpkg/bar", "deleteLogs" : true}""".parseJson
  }

  it should "computeFields with nspath as namespace when path value is simple name" in {
    val js = """{
               |  "namespace": "foons",
               |  "name":"bar",
               |  "annotations": [
               |    {
               |      "key": "path",
               |      "value": "baraction"
               |    }
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computedFields(js) shouldBe """{"nspath": "foons/bar", "deleteLogs" : true}""".parseJson
  }

  behavior of "ActivationHandler computeActivationView"

  it should "include only listed fields" in {
    val js = """{
               |  "namespace" : "foo",
               |  "extra" : false,
               |  "cause" : 204
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
               |  "namespace" : "foo",
               |  "cause" : 204
               |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computeView("foo", "activations", js) shouldBe result
  }

  it should "include duration when end is non zero" in {
    val js = """{
               |  "namespace" : "foo",
               |  "start" : 5,
               |  "end"   : 9,
               |  "cause" : 204
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace" : "foo",
                   |  "start" : 5,
                   |  "end"   : 9,
                   |  "duration" : 4,
                   |  "cause" : 204
                   |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computeView("foo", "activations", js) shouldBe result
  }

  it should "not include duration when end is zero" in {
    val js = """{
               |  "namespace" : "foo",
               |  "start" : 5,
               |  "end"   : 0,
               |  "cause" : 204
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace" : "foo",
                   |  "start" : 5,
                   |  "cause" : 204
                   |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computeView("foo", "activations", js) shouldBe result
  }

  it should "include statusCode" in {
    val js = """{
               |  "namespace": "foo",
               |  "response": {"statusCode" : 404}
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace": "foo",
                   |  "statusCode" : 404
                   |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computeView("foo", "activations", js) shouldBe result
  }

  it should "not include statusCode" in {
    val js = """{
               |  "namespace": "foo",
               |  "response": {"status" : 404}
               |}""".stripMargin.parseJson.asJsObject

    val result = """{
                   |  "namespace": "foo"
                   |}""".stripMargin.parseJson.asJsObject
    ActivationHandler.computeView("foo", "activations", js) shouldBe result
  }

  behavior of "ActivationHandler fieldsRequiredForView"

  it should "match the expected field names" in {
    ActivationHandler.fieldsRequiredForView("foo", "activations") shouldBe
      Set(
        "namespace",
        "name",
        "version",
        "publish",
        "annotations",
        "activationId",
        "start",
        "cause",
        "end",
        "response.statusCode")
  }

  it should "throw UnsupportedView exception" in {
    intercept[UnsupportedView] {
      ActivationHandler.fieldsRequiredForView("foo", "unknown")
    }
  }

  behavior of "SubjectHandler computeSubjectView"

  it should "match subject with namespace" in {
    val js = """{
               |  "subject": "foo",
               |  "uuid": "u1",
               |  "key" : "k1"
               |}""".stripMargin.parseJson.asJsObject
    SubjectHandler.findMatchingSubject(List("foo"), js).value shouldBe SubjectView("foo", "u1", "k1")
  }

  it should "match subject with child namespace" in {
    val js = """{
               |  "subject": "bar",
               |  "uuid": "u1",
               |  "key" : "k1",
               |  "namespaces" : [
               |    {"name": "foo", "uuid":"u2", "key":"k2"}
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    SubjectHandler.findMatchingSubject(List("foo"), js).value shouldBe
      SubjectView("foo", "u2", "k2", matchInNamespace = true)
  }

  it should "match subject with uuid and key" in {
    val js = """{
               |  "subject": "foo",
               |  "uuid": "u1",
               |  "key" : "k1"
               |}""".stripMargin.parseJson.asJsObject
    SubjectHandler.findMatchingSubject(List("u1", "k1"), js).value shouldBe
      SubjectView("foo", "u1", "k1")
  }

  it should "match subject with child namespace with uuid and key" in {
    val js = """{
               |  "subject": "bar",
               |  "uuid": "u1",
               |  "key" : "k1",
               |  "namespaces" : [
               |    {"name": "foo", "uuid":"u2", "key":"k2"}
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    SubjectHandler.findMatchingSubject(List("u2", "k2"), js).value shouldBe
      SubjectView("foo", "u2", "k2", matchInNamespace = true)
  }

  it should "throw exception when namespace match but key missing" in {
    val js = """{
               |  "subject": "foo",
               |  "uuid": "u1",
               |  "blocked" : true
               |}""".stripMargin.parseJson.asJsObject
    SubjectHandler.findMatchingSubject(List("foo"), js) shouldBe empty
  }

  behavior of "SubjectHandler transformViewResult"

  it should "json should match format of CouchDB response" in {
    implicit val tid: TransactionId = transid()
    val js = """{
               |  "_id": "bar",
               |  "subject": "bar",
               |  "uuid": "u1",
               |  "key" : "k1",
               |  "namespaces" : [
               |    {"name": "foo", "uuid":"u2", "key":"k2"}
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    val result = """{
                   |  "id": "bar",
                   |  "key": [
                   |    "u2",
                   |    "k2"
                   |  ],
                   |  "value": {
                   |    "_id": "foo/limits",
                   |    "namespace": "foo",
                   |    "uuid": "u2",
                   |    "key": "k2"
                   |  },
                   |  "doc": null
                   |}""".stripMargin.parseJson.asJsObject
    val queryKey = List("u2", "k2")
    SubjectHandler
      .transformViewResult("subjects", "identities", queryKey, queryKey, includeDocs = true, js, TestDocumentProvider())
      .futureValue shouldBe Seq(result)
  }

  it should "should return none when passed object does not passes view criteria" in {
    implicit val tid: TransactionId = transid()
    val js = """{
               |  "_id": "bar",
               |  "subject": "bar",
               |  "uuid": "u1",
               |  "key" : "k1",
               |  "namespaces" : [
               |    {"name": "foo", "uuid":"u2", "key":"k2"},
               |    {"name": "foo2", "uuid":"u3", "key":"k3"}
               |  ]
               |}""".stripMargin.parseJson.asJsObject

    val queryKey = List("u2", "k3")
    SubjectHandler
      .transformViewResult("subjects", "identities", queryKey, queryKey, includeDocs = true, js, TestDocumentProvider())
      .futureValue shouldBe empty
  }

  behavior of "SubjectHandler blacklisted namespaces"

  it should "match limits with 0 concurrentInvocations" in {
    val js = """{
               |  "_id": "bar/limits",
               |  "concurrentInvocations": 0
               |}""".stripMargin.parseJson.asJsObject
    val result = """{
                   |  "id": "bar/limits",
                   |  "key": "bar",
                   |  "value": 1
                   |}""".stripMargin.parseJson.asJsObject
    blacklistingResults(js) shouldBe Seq(result)
  }

  it should "match limits with 0 invocationsPerMinute" in {
    val js = """{
               |  "_id": "bar/limits",
               |  "concurrentInvocations": 10,
               |  "invocationsPerMinute": 0
               |}""".stripMargin.parseJson.asJsObject
    val result = """{
                   |  "id": "bar/limits",
                   |  "key": "bar",
                   |  "value": 1
                   |}""".stripMargin.parseJson.asJsObject
    blacklistingResults(js) shouldBe Seq(result)
  }

  it should "not match limits with invocationsPerMinute and concurrentInvocations defined" in {
    val js = """{
               |  "_id": "bar/limits",
               |  "concurrentInvocations": 10,
               |  "invocationsPerMinute": 40
               |}""".stripMargin.parseJson.asJsObject
    blacklistingResults(js) shouldBe empty
  }

  it should "list all namespaces of blocked subject" in {
    val js = """{
               |  "_id": "bar",
               |  "blocked": true,
               |  "subject": "bar",
               |  "namespaces" : [
               |    {"name": "foo", "uuid":"u2", "key":"k2"},
               |    {"name": "foo2", "uuid":"u3", "key":"k3"}
               |  ]
               |}""".stripMargin.parseJson.asJsObject
    val r1 = """{
               |  "id": "bar",
               |  "key": "foo",
               |  "value": 1
               |}""".stripMargin.parseJson.asJsObject
    val r2 = """{
               |  "id": "bar",
               |  "key": "foo2",
               |  "value": 1
               |}""".stripMargin.parseJson.asJsObject
    blacklistingResults(js).toSet shouldBe Set(r1, r2)
  }

  it should "list no namespace of unblocked subject" in {
    val js = """{
               |  "_id": "bar",
               |  "subject": "bar",
               |  "namespaces" : [
               |    {"name": "foo", "uuid":"u2", "key":"k2"},
               |    {"name": "foo2", "uuid":"u3", "key":"k3"}
               |  ]
               |}""".stripMargin.parseJson.asJsObject

    blacklistingResults(js) shouldBe empty
  }

  private def blacklistingResults(js: JsObject) = {
    implicit val tid: TransactionId = transid()
    SubjectHandler
      .transformViewResult(
        "namespaceThrottlings",
        "blockedNamespaces",
        List.empty,
        List.empty,
        includeDocs = false,
        js,
        TestDocumentProvider())
      .futureValue
  }

  private case class TestDocumentProvider(js: Option[JsObject]) extends DocumentProvider {
    override protected[database] def get(id: DocId)(implicit transid: TransactionId) = Future.successful(js)
  }

  private object TestDocumentProvider {
    def apply(js: JsObject): DocumentProvider = new TestDocumentProvider(Some(js))
    def apply(): DocumentProvider = new TestDocumentProvider(None)
  }
}
