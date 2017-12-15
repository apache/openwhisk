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

package whisk.core.controller.test

import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes._

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.server.Route

import spray.json._
import spray.json.DefaultJsonProtocol._

import whisk.core.controller.WhiskActionsApi
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.http.ErrorResponse
import whisk.http.Messages

/**
 * Tests Actions API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class ActionsApiTests extends ControllerTestCommon with WhiskActionsApi {

  /** Actions API tests */
  behavior of "Actions API"

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("action_tests")
  val actionLimit = Exec.sizeLimit
  val parametersLimit = Parameters.sizeLimit

  //// GET /actions
  it should "list actions by default namespace" in {
    implicit val tid = transid()
    val actions = (1 to 2).map { i =>
      WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    }.toList
    actions foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskAction, namespace, 2)
    Get(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      actions.length should be(response.length)
      actions forall { a =>
        response contains a.summaryAsJson
      } should be(true)
    }
  }

  // ?docs disabled
  ignore should "list action by default namespace with full docs" in {
    implicit val tid = transid()
    val actions = (1 to 2).map { i =>
      WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    }.toList
    actions foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskAction, namespace, 2)
    Get(s"$collectionPath?docs=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[WhiskAction]]
      actions.length should be(response.length)
      actions forall { a =>
        response contains a
      } should be(true)
    }
  }

  it should "list action with explicit namespace" in {
    implicit val tid = transid()
    val actions = (1 to 2).map { i =>
      WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    }.toList
    actions foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskAction, namespace, 2)
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      actions.length should be(response.length)
      actions forall { a =>
        response contains a.summaryAsJson
      } should be(true)
    }

    // it should "reject list action with explicit namespace not owned by subject" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "list should reject request with post" in {
    implicit val tid = transid()
    Post(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
      status should be(MethodNotAllowed)
    }
  }

  //// GET /actions/name
  it should "get action by name in default namespace" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    put(entityStore, action)
    Get(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(action)
    }
  }

  it should "get action by name in explicit namespace" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    put(entityStore, action)
    Get(s"/$namespace/${collection.path}/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(action)
    }

    // it should "reject get action by name in explicit namespace not owned by subject" in
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${action.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "get action using code query parameter" in {
    implicit val tid = transid()

    // BlackBox: binary: true, main: bbMain
    val bbAction1 = WhiskAction(namespace, aname(), bb("bb", "RHViZWU=", Some("bbMain")))
    val bbAction1Content = Map("exec" -> Map(
      "kind" -> Exec.BLACKBOX,
      "code" -> "RHViZWU=",
      "image" -> "bb",
      "main" -> "bbMain")).toJson.asJsObject
    val bbAction1ExpectedWhiskAction = WhiskAction(
      bbAction1.namespace,
      bbAction1.name,
      bbAction1.exec,
      bbAction1.parameters,
      bbAction1.limits,
      bbAction1.version,
      bbAction1.publish,
      bbAction1.annotations ++ Parameters(WhiskAction.execFieldName, Exec.BLACKBOX))
    val bbAction1ExpectedWhiskActionMetaData = WhiskActionMetaData(
      bbAction1.namespace,
      bbAction1.name,
      blackBoxMetaData("bb", Some("bbMain"), true),
      bbAction1.parameters,
      bbAction1.limits,
      bbAction1.version,
      bbAction1.publish,
      bbAction1.annotations ++ Parameters(WhiskActionMetaData.execFieldName, Exec.BLACKBOX))

    // BlackBox: binary: false, main: bbMain
    val bbAction2 = WhiskAction(namespace, aname(), bb("bb", "", Some("bbMain")))
    val bbAction2Content =
      Map("exec" -> Map("kind" -> Exec.BLACKBOX, "code" -> "", "image" -> "bb", "main" -> "bbMain")).toJson.asJsObject
    val bbAction2ExpectedWhiskAction = WhiskAction(
      bbAction2.namespace,
      bbAction2.name,
      bbAction2.exec,
      bbAction2.parameters,
      bbAction2.limits,
      bbAction2.version,
      bbAction2.publish,
      bbAction2.annotations ++ Parameters(WhiskAction.execFieldName, Exec.BLACKBOX))
    val bbAction2ExpectedWhiskActionMetaData = WhiskActionMetaData(
      bbAction2.namespace,
      bbAction2.name,
      blackBoxMetaData("bb", Some("bbMain"), false),
      bbAction2.parameters,
      bbAction2.limits,
      bbAction2.version,
      bbAction2.publish,
      bbAction2.annotations ++ Parameters(WhiskActionMetaData.execFieldName, Exec.BLACKBOX))

    // BlackBox: binary: true, no main
    val bbAction3 = WhiskAction(namespace, aname(), bb("bb", "RHViZWU="))
    val bbAction3Content =
      Map("exec" -> Map("kind" -> Exec.BLACKBOX, "code" -> "RHViZWU=", "image" -> "bb")).toJson.asJsObject
    val bbAction3ExpectedWhiskAction = WhiskAction(
      bbAction3.namespace,
      bbAction3.name,
      bbAction3.exec,
      bbAction3.parameters,
      bbAction3.limits,
      bbAction3.version,
      bbAction3.publish,
      bbAction3.annotations ++ Parameters(WhiskAction.execFieldName, Exec.BLACKBOX))
    val bbAction3ExpectedWhiskActionMetaData = WhiskActionMetaData(
      bbAction3.namespace,
      bbAction3.name,
      blackBoxMetaData("bb", None, true),
      bbAction3.parameters,
      bbAction3.limits,
      bbAction3.version,
      bbAction3.publish,
      bbAction3.annotations ++ Parameters(WhiskActionMetaData.execFieldName, Exec.BLACKBOX))

    // BlackBox: binary: false, no main
    val bbAction4 = WhiskAction(namespace, aname(), bb("bb", ""))
    val bbAction4Content = Map("exec" -> Map("kind" -> Exec.BLACKBOX, "code" -> "", "image" -> "bb")).toJson.asJsObject
    val bbAction4ExpectedWhiskAction = WhiskAction(
      bbAction4.namespace,
      bbAction4.name,
      bbAction4.exec,
      bbAction4.parameters,
      bbAction4.limits,
      bbAction4.version,
      bbAction4.publish,
      bbAction4.annotations ++ Parameters(WhiskAction.execFieldName, Exec.BLACKBOX))
    val bbAction4ExpectedWhiskActionMetaData = WhiskActionMetaData(
      bbAction4.namespace,
      bbAction4.name,
      blackBoxMetaData("bb", None, false),
      bbAction4.parameters,
      bbAction4.limits,
      bbAction4.version,
      bbAction4.publish,
      bbAction4.annotations ++ Parameters(WhiskActionMetaData.execFieldName, Exec.BLACKBOX))

    // Attachment: binary: true, main: javaMain
    val javaAction1 = WhiskAction(namespace, aname(), javaDefault("RHViZWU=", Some("javaMain")))
    val javaAction1Content =
      Map("exec" -> Map("kind" -> JAVA_DEFAULT, "code" -> "RHViZWU=", "main" -> "javaMain")).toJson.asJsObject
    val javaAction1ExpectedWhiskAction = WhiskAction(
      javaAction1.namespace,
      javaAction1.name,
      javaAction1.exec,
      javaAction1.parameters,
      javaAction1.limits,
      javaAction1.version,
      javaAction1.publish,
      javaAction1.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT))
    val javaAction1ExpectedWhiskActionMetaData = WhiskActionMetaData(
      javaAction1.namespace,
      javaAction1.name,
      javaMetaData(Some("javaMain"), true),
      javaAction1.parameters,
      javaAction1.limits,
      javaAction1.version,
      javaAction1.publish,
      javaAction1.annotations ++ Parameters(WhiskActionMetaData.execFieldName, JAVA_DEFAULT))

    // String: binary: true, main: jsMain
    val jsAction1 = WhiskAction(namespace, aname(), jsDefault("RHViZWU=", Some("jsMain")))
    val jsAction1Content =
      Map("exec" -> Map("kind" -> NODEJS6, "code" -> "RHViZWU=", "main" -> "jsMain")).toJson.asJsObject
    val jsAction1ExpectedWhiskAction = WhiskAction(
      jsAction1.namespace,
      jsAction1.name,
      jsAction1.exec,
      jsAction1.parameters,
      jsAction1.limits,
      jsAction1.version,
      jsAction1.publish,
      jsAction1.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6))
    val jsAction1ExpectedWhiskActionMetaData = WhiskActionMetaData(
      jsAction1.namespace,
      jsAction1.name,
      js6MetaData(Some("jsMain"), true),
      jsAction1.parameters,
      jsAction1.limits,
      jsAction1.version,
      jsAction1.publish,
      jsAction1.annotations ++ Parameters(WhiskActionMetaData.execFieldName, NODEJS6))

    // String: binary: false, main: jsMain
    val jsAction2 = WhiskAction(namespace, aname(), jsDefault("", Some("jsMain")))
    val jsAction2Content = Map("exec" -> Map("kind" -> NODEJS6, "code" -> "", "main" -> "jsMain")).toJson.asJsObject
    val jsAction2ExpectedWhiskAction = WhiskAction(
      jsAction2.namespace,
      jsAction2.name,
      jsAction2.exec,
      jsAction2.parameters,
      jsAction2.limits,
      jsAction2.version,
      jsAction2.publish,
      jsAction2.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6))
    val jsAction2ExpectedWhiskActionMetaData = WhiskActionMetaData(
      jsAction2.namespace,
      jsAction2.name,
      js6MetaData(Some("jsMain"), false),
      jsAction2.parameters,
      jsAction2.limits,
      jsAction2.version,
      jsAction2.publish,
      jsAction2.annotations ++ Parameters(WhiskActionMetaData.execFieldName, NODEJS6))

    // String: binary: true, no main
    val jsAction3 = WhiskAction(namespace, aname(), jsDefault("RHViZWU="))
    val jsAction3Content = Map("exec" -> Map("kind" -> NODEJS6, "code" -> "RHViZWU=")).toJson.asJsObject
    val jsAction3ExpectedWhiskAction = WhiskAction(
      jsAction3.namespace,
      jsAction3.name,
      jsAction3.exec,
      jsAction3.parameters,
      jsAction3.limits,
      jsAction3.version,
      jsAction3.publish,
      jsAction3.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6))
    val jsAction3ExpectedWhiskActionMetaData = WhiskActionMetaData(
      jsAction3.namespace,
      jsAction3.name,
      js6MetaData(None, true),
      jsAction3.parameters,
      jsAction3.limits,
      jsAction3.version,
      jsAction3.publish,
      jsAction3.annotations ++ Parameters(WhiskActionMetaData.execFieldName, NODEJS6))

    // String: binary: false, no main
    val jsAction4 = WhiskAction(namespace, aname(), jsDefault(""))
    val jsAction4Content = Map("exec" -> Map("kind" -> NODEJS6, "code" -> "")).toJson.asJsObject
    val jsAction4ExpectedWhiskAction = WhiskAction(
      jsAction4.namespace,
      jsAction4.name,
      jsAction4.exec,
      jsAction4.parameters,
      jsAction4.limits,
      jsAction4.version,
      jsAction4.publish,
      jsAction4.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6))
    val jsAction4ExpectedWhiskActionMetaData = WhiskActionMetaData(
      jsAction4.namespace,
      jsAction4.name,
      js6MetaData(None, false),
      jsAction4.parameters,
      jsAction4.limits,
      jsAction4.version,
      jsAction4.publish,
      jsAction4.annotations ++ Parameters(WhiskActionMetaData.execFieldName, NODEJS6))

    // Sequence
    val component = WhiskAction(namespace, aname(), jsDefault("??"))
    put(entityStore, component)
    val components = Vector(s"/$namespace/${component.name}").map(stringToFullyQualifiedName(_))
    val seqAction = WhiskAction(namespace, aname(), sequence(components), seqParameters(components))
    val seqActionContent = JsObject(
      "exec" -> JsObject("kind" -> "sequence".toJson, "components" -> JsArray(s"/$namespace/${component.name}".toJson)))
    val seqActionExpectedWhiskAction = WhiskAction(
      seqAction.namespace,
      seqAction.name,
      seqAction.exec,
      seqAction.parameters,
      seqAction.limits,
      seqAction.version,
      seqAction.publish,
      seqAction.annotations ++ Parameters(WhiskAction.execFieldName, "sequence"))
    val seqActionExpectedWhiskActionMetaData = WhiskActionMetaData(
      seqAction.namespace,
      seqAction.name,
      sequenceMetaData(components),
      seqAction.parameters,
      seqAction.limits,
      seqAction.version,
      seqAction.publish,
      seqAction.annotations ++ Parameters(WhiskActionMetaData.execFieldName, "sequence"))

    val actions = Seq(
      (bbAction1, bbAction1Content, bbAction1ExpectedWhiskAction, bbAction1ExpectedWhiskActionMetaData),
      (bbAction2, bbAction2Content, bbAction2ExpectedWhiskAction, bbAction2ExpectedWhiskActionMetaData),
      (bbAction3, bbAction3Content, bbAction3ExpectedWhiskAction, bbAction3ExpectedWhiskActionMetaData),
      (bbAction4, bbAction4Content, bbAction4ExpectedWhiskAction, bbAction4ExpectedWhiskActionMetaData),
      (javaAction1, javaAction1Content, javaAction1ExpectedWhiskAction, javaAction1ExpectedWhiskActionMetaData),
      (jsAction1, jsAction1Content, jsAction1ExpectedWhiskAction, jsAction1ExpectedWhiskActionMetaData),
      (jsAction2, jsAction2Content, jsAction2ExpectedWhiskAction, jsAction2ExpectedWhiskActionMetaData),
      (jsAction3, jsAction3Content, jsAction3ExpectedWhiskAction, jsAction3ExpectedWhiskActionMetaData),
      (jsAction4, jsAction4Content, jsAction4ExpectedWhiskAction, jsAction4ExpectedWhiskActionMetaData),
      (seqAction, seqActionContent, seqActionExpectedWhiskAction, seqActionExpectedWhiskActionMetaData))

    actions.foreach {
      case (action, content, expectedWhiskAction, expectedWhiskActionMetaData) =>
        Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[WhiskAction]
          response should be(expectedWhiskAction)
        }

        Get(s"$collectionPath/${action.name}?code=false") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val responseJson = responseAs[JsObject]
          responseJson.fields("exec").asJsObject.fields should not(contain key "code")
          val response = responseAs[WhiskActionMetaData]
          response should be(expectedWhiskActionMetaData)
        }

        Seq(s"$collectionPath/${action.name}", s"$collectionPath/${action.name}?code=true").foreach { path =>
          Get(path) ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(expectedWhiskAction)
          }
        }

        Delete(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[WhiskAction]
          response should be(expectedWhiskAction)
        }
    }
  }

  it should "report NotFound for get non existent action" in {
    implicit val tid = transid()
    Get(s"$collectionPath/xyz") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  it should "report Conflict if the name was of a different type" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname())
    put(entityStore, trigger)
    Get(s"/$namespace/${collection.path}/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Conflict)
    }
  }

  it should "reject long entity names" in {
    implicit val tid = transid()
    val longName = "a" * (EntityName.ENTITY_NAME_MAX_LENGTH + 1)
    Get(s"/$longName/${collection.path}/$longName") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] shouldBe {
        Messages.entityNameTooLong(
          SizeError(namespaceDescriptionForSizeError, longName.length.B, EntityName.ENTITY_NAME_MAX_LENGTH.B))
      }
    }

    Seq(
      s"/$namespace/${collection.path}/$longName",
      s"/$namespace/${collection.path}/pkg/$longName",
      s"/$namespace/${collection.path}/$longName/a",
      s"/$namespace/${collection.path}/$longName/$longName").foreach { p =>
      Get(p) ~> Route.seal(routes(creds)) ~> check {
        status should be(BadRequest)
        responseAs[String] shouldBe {
          Messages.entityNameTooLong(
            SizeError(segmentDescriptionForSizeError, longName.length.B, EntityName.ENTITY_NAME_MAX_LENGTH.B))
        }
      }
    }
  }

  //// DEL /actions/name
  it should "delete action by name" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    put(entityStore, action)

    // it should "reject delete action by name not owned by subject" in
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${action.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Delete(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(action)
    }
  }

  it should "report NotFound for delete non existent action" in {
    implicit val tid = transid()
    Delete(s"$collectionPath/xyz") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  //// PUT /actions/name
  it should "put should reject request missing json content" in {
    implicit val tid = transid()
    Put(s"$collectionPath/xxx", "") ~> Route.seal(routes(creds)) ~> check {
      val response = responseAs[String]
      status should be(UnsupportedMediaType)
    }
  }

  it should "put should reject request missing property exec" in {
    implicit val tid = transid()
    val content = """|{"name":"name","publish":true}""".stripMargin.parseJson.asJsObject
    Put(s"$collectionPath/xxx", content) ~> Route.seal(routes(creds)) ~> check {
      val response = responseAs[String]
      status should be(BadRequest)
    }
  }

  it should "put should reject request with malformed property exec" in {
    implicit val tid = transid()
    val content = """|{"name":"name",
                         |"publish":true,
                         |"exec":""}""".stripMargin.parseJson.asJsObject
    Put(s"$collectionPath/xxx", content) ~> Route.seal(routes(creds)) ~> check {
      val response = responseAs[String]
      status should be(BadRequest)
    }
  }

  it should "reject create with exec which is too big" in {
    implicit val tid = transid()
    val code = "a" * (actionLimit.toBytes.toInt + 1)
    val exec: Exec = jsDefault(code)
    val content = JsObject("exec" -> exec.toJson)
    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(RequestEntityTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskAction.execFieldName, exec.size, Exec.sizeLimit))
      }
    }
  }

  it should "reject update with exec which is too big" in {
    implicit val tid = transid()
    val oldCode = "function main()"
    val code = "a" * (actionLimit.toBytes.toInt + 1)
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val exec: Exec = jsDefault(code)
    val content = JsObject("exec" -> exec.toJson)
    put(entityStore, action)
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(RequestEntityTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskAction.execFieldName, exec.size, Exec.sizeLimit))
      }
    }
  }

  it should "reject create with parameters which are too big" in {
    implicit val tid = transid()
    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val parameters = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val content = s"""{"exec":{"kind":"nodejs","code":"??"},"parameters":$parameters}""".stripMargin
    Put(s"$collectionPath/${aname()}", content.parseJson.asJsObject) ~> Route.seal(routes(creds)) ~> check {
      status should be(RequestEntityTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.paramsFieldName, parameters.size, Parameters.sizeLimit))
      }
    }
  }

  it should "reject create with annotations which are too big" in {
    implicit val tid = transid()
    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val annotations = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val content = s"""{"exec":{"kind":"nodejs","code":"??"},"annotations":$annotations}""".stripMargin
    Put(s"$collectionPath/${aname()}", content.parseJson.asJsObject) ~> Route.seal(routes(creds)) ~> check {
      status should be(RequestEntityTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.annotationsFieldName, annotations.size, Parameters.sizeLimit))
      }
    }
  }

  it should "reject activation with entity which is too big" in {
    implicit val tid = transid()
    val code = "a" * (allowedActivationEntitySize.toInt + 1)
    val content = s"""{"a":"$code"}""".stripMargin
    Post(s"$collectionPath/${aname()}", content.parseJson.asJsObject) ~> Route.seal(routes(creds)) ~> check {
      status should be(RequestEntityTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(
          SizeError(fieldDescriptionForSizeError, (content.length).B, allowedActivationEntitySize.B))
      }
    }
  }

  it should "put should accept request with missing optional properties" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val content = WhiskActionPut(Some(action.exec))
    Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(
        WhiskAction(
          action.namespace,
          action.name,
          action.exec,
          action.parameters,
          action.limits,
          action.version,
          action.publish,
          action.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6)))
    }
  }

  it should "put should accept blackbox exec with empty code property" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), bb("bb"))
    val content = Map("exec" -> Map("kind" -> "blackbox", "code" -> "", "image" -> "bb")).toJson.asJsObject
    Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(
        WhiskAction(
          action.namespace,
          action.name,
          action.exec,
          action.parameters,
          action.limits,
          action.version,
          action.publish,
          action.annotations ++ Parameters(WhiskAction.execFieldName, Exec.BLACKBOX)))
      response.exec shouldBe an[BlackBoxExec]
      response.exec.asInstanceOf[BlackBoxExec].code shouldBe empty
    }
  }

  it should "put should accept blackbox exec with non-empty code property" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), bb("bb", "cc"))
    val content = Map("exec" -> Map("kind" -> "blackbox", "code" -> "cc", "image" -> "bb")).toJson.asJsObject
    Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(
        WhiskAction(
          action.namespace,
          action.name,
          action.exec,
          action.parameters,
          action.limits,
          action.version,
          action.publish,
          action.annotations ++ Parameters(WhiskAction.execFieldName, Exec.BLACKBOX)))
      response.exec shouldBe an[BlackBoxExec]
      val bb = response.exec.asInstanceOf[BlackBoxExec]
      bb.code shouldBe Some("cc")
      bb.binary shouldBe false
    }
  }

  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  private def seqParameters(seq: Vector[FullyQualifiedEntityName]) =
    Parameters("_actions", seq.map("/" + _.asString).toJson)

  // this test is sneaky; the installation of the sequence is done directly in the db
  // and api checks are skipped
  it should "reset parameters when changing sequence action to non sequence" in {
    implicit val tid = transid()
    val components = Vector("x/a", "x/b").map(stringToFullyQualifiedName(_))
    val action = WhiskAction(namespace, aname(), sequence(components), seqParameters(components))
    val content = WhiskActionPut(Some(jsDefault("")))
    put(entityStore, action, false)

    // create an action sequence
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response.exec.kind should be(NODEJS6)
      response.parameters shouldBe Parameters()
    }
  }

  // this test is sneaky; the installation of the sequence is done directly in the db
  // and api checks are skipped
  it should "preserve new parameters when changing sequence action to non sequence" in {
    implicit val tid = transid()
    val components = Vector("x/a", "x/b").map(stringToFullyQualifiedName(_))
    val action = WhiskAction(namespace, aname(), sequence(components), seqParameters(components))
    val content = WhiskActionPut(Some(jsDefault("")), parameters = Some(Parameters("a", "A")))
    put(entityStore, action, false)

    // create an action sequence
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response.exec.kind should be(NODEJS6)
      response.parameters should be(Parameters("a", "A"))
    }
  }

  it should "put should accept request with parameters property" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val content = WhiskActionPut(Some(action.exec), Some(action.parameters))

    // it should "reject put action in namespace not owned by subject" in
    val auser = WhiskAuthHelpers.newIdentity()
    Put(s"/$namespace/${collection.path}/${action.name}", content) ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(
        WhiskAction(
          action.namespace,
          action.name,
          action.exec,
          action.parameters,
          action.limits,
          action.version,
          action.publish,
          action.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6)))
    }
  }

  it should "put should reject request with parameters property as jsobject" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val content = WhiskActionPut(Some(action.exec), Some(action.parameters))
    val params = """{ "parameters": { "a": "b" } }""".parseJson.asJsObject
    val json = JsObject(WhiskActionPut.serdes.write(content).asJsObject.fields ++ params.fields)
    Put(s"$collectionPath/${action.name}", json) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "put should accept request with limits property" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val content = WhiskActionPut(
      Some(action.exec),
      Some(action.parameters),
      Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))
    Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(
        WhiskAction(
          action.namespace,
          action.name,
          action.exec,
          action.parameters,
          action.limits,
          action.version,
          action.publish,
          action.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6)))
    }
  }

  it should "put and then get an action from cache" in {
    val javaAction =
      WhiskAction(namespace, aname(), javaDefault("ZHViZWU=", Some("hello")), annotations = Parameters("exec", "java"))
    val nodeAction = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val actions = Seq((javaAction, JAVA_DEFAULT), (nodeAction, NODEJS6))

    actions.foreach {
      case (action, kind) =>
        val content = WhiskActionPut(
          Some(action.exec),
          Some(action.parameters),
          Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))

        // first request invalidates any previous entries and caches new result
        Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)(transid())) ~> check {
          status should be(OK)
          val response = responseAs[WhiskAction]
          response should be(
            WhiskAction(
              action.namespace,
              action.name,
              action.exec,
              action.parameters,
              action.limits,
              action.version,
              action.publish,
              action.annotations ++ Parameters(WhiskAction.execFieldName, kind)))
        }
        stream.toString should include(s"caching ${CacheKey(action)}")
        stream.toString should not include (s"invalidating ${CacheKey(action)} on delete")
        stream.reset()

        // second request should fetch from cache
        Get(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)(transid())) ~> check {
          status should be(OK)
          val response = responseAs[WhiskAction]
          response should be(
            WhiskAction(
              action.namespace,
              action.name,
              action.exec,
              action.parameters,
              action.limits,
              action.version,
              action.publish,
              action.annotations ++ Parameters(WhiskAction.execFieldName, kind)))
        }
        stream.toString should include(s"serving from cache: ${CacheKey(action)}")
        stream.reset()

        // update should invalidate cache
        Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)(transid())) ~> check {
          status should be(OK)
          val response = responseAs[WhiskAction]
          response should be {
            WhiskAction(
              action.namespace,
              action.name,
              action.exec,
              action.parameters,
              action.limits,
              action.version.upPatch,
              action.publish,
              action.annotations ++ Parameters(WhiskAction.execFieldName, kind))
          }
        }
        stream.toString should include(s"entity exists, will try to update '$action'")
        stream.toString should include(s"invalidating ${CacheKey(action)}")
        stream.toString should include(s"caching ${CacheKey(action)}")
        stream.reset()

        // delete should invalidate cache
        Delete(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)(transid())) ~> check {
          status should be(OK)
          val response = responseAs[WhiskAction]
          response should be(
            WhiskAction(
              action.namespace,
              action.name,
              action.exec,
              action.parameters,
              action.limits,
              action.version.upPatch,
              action.publish,
              action.annotations ++ Parameters(WhiskAction.execFieldName, kind)))
        }
        stream.toString should include(s"invalidating ${CacheKey(action)}")
        stream.reset()
    }
  }

  it should "reject put with conflict for pre-existing action" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val content = WhiskActionPut(Some(action.exec))
    put(entityStore, action)
    Put(s"$collectionPath/${action.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Conflict)
    }
  }

  it should "update action with a put" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val content = WhiskActionPut(Some(jsDefault("_")), Some(Parameters("x", "X")))
    put(entityStore, action)
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be {
        WhiskAction(
          action.namespace,
          action.name,
          content.exec.get,
          content.parameters.get,
          version = action.version.upPatch,
          annotations = action.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6))
      }
    }
  }

  it should "update action parameters with a put" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val content = WhiskActionPut(parameters = Some(Parameters("x", "X")))
    put(entityStore, action)
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be {
        WhiskAction(
          action.namespace,
          action.name,
          action.exec,
          content.parameters.get,
          version = action.version.upPatch,
          annotations = action.annotations ++ Parameters(WhiskAction.execFieldName, NODEJS6))
      }
    }
  }

  //// POST /actions/name
  it should "invoke an action with arguments, nonblocking" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    val args = JsObject("xxx" -> "yyy".toJson)
    put(entityStore, action)

    // it should "reject post to action in namespace not owned by subject"
    val auser = WhiskAuthHelpers.newIdentity()
    Post(s"/$namespace/${collection.path}/${action.name}", args) ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Post(s"$collectionPath/${action.name}", args) ~> Route.seal(routes(creds)) ~> check {
      status should be(Accepted)
      val response = responseAs[JsObject]
      response.fields("activationId") should not be None
    }

    // it should "ignore &result when invoking nonblocking action"
    Post(s"$collectionPath/${action.name}?result=true", args) ~> Route.seal(routes(creds)) ~> check {
      status should be(Accepted)
      val response = responseAs[JsObject]
      response.fields("activationId") should not be None
    }
  }

  it should "invoke an action, nonblocking" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    put(entityStore, action)
    Post(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Accepted)
      val response = responseAs[JsObject]
      response.fields("activationId") should not be None
    }
  }

  it should "invoke an action, blocking with default timeout" in {
    implicit val tid = transid()
    val action = WhiskAction(
      namespace,
      aname(),
      jsDefault("??"),
      limits = ActionLimits(TimeLimit(1 second), MemoryLimit(), LogLimit()))
    put(entityStore, action)
    Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
      // status should be accepted because there is no active ack response and
      // db polling will fail since there is no record of the activation
      status should be(Accepted)
      val response = responseAs[JsObject]
      response.fields("activationId") should not be None
    }
  }

  it should "invoke an action, blocking and retrieve result via db polling" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val activation = WhiskActivation(
      action.namespace,
      action.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now,
      response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))))
    put(entityStore, action)
    // storing the activation in the db will allow the db polling to retrieve it
    // the test harness makes sure the activation id observed by the test matches
    // the one generated by the api handler
    put(activationStore, activation)
    try {
      Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.withoutLogs.toExtendedJson)
      }

      // repeat invoke, get only result back
      Post(s"$collectionPath/${action.name}?blocking=true&result=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.resultAsJson)
      }
    } finally {
      deleteActivation(activation.docid)
    }
  }

  it should "invoke an action, blocking and retrieve result via active ack" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val activation = WhiskActivation(
      action.namespace,
      action.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now,
      response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))))
    put(entityStore, action)

    try {
      // do not store the activation in the db, instead register it as the response to generate on active ack
      loadBalancer.whiskActivationStub = Some((1.milliseconds, activation))

      Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.withoutLogs.toExtendedJson)
      }

      // repeat invoke, get only result back
      Post(s"$collectionPath/${action.name}?blocking=true&result=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.resultAsJson)
      }
    } finally {
      loadBalancer.whiskActivationStub = None
    }
  }

  it should "invoke an action, blocking up to specified timeout and retrieve result via active ack" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val activation = WhiskActivation(
      action.namespace,
      action.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now,
      response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))))
    put(entityStore, action)

    try {
      // do not store the activation in the db, instead register it as the response to generate on active ack
      loadBalancer.whiskActivationStub = Some((300.milliseconds, activation))

      Post(s"$collectionPath/${action.name}?blocking=true&timeout=0") ~> Route.seal(routes(creds)) ~> check {
        status shouldBe BadRequest
        responseAs[String] should include(Messages.invalidTimeout(WhiskActionsApi.maxWaitForBlockingActivation))
      }

      Post(s"$collectionPath/${action.name}?blocking=true&timeout=65000") ~> Route.seal(routes(creds)) ~> check {
        status shouldBe BadRequest
        responseAs[String] should include(Messages.invalidTimeout(WhiskActionsApi.maxWaitForBlockingActivation))
      }

      // will not wait long enough should get accepted status
      Post(s"$collectionPath/${action.name}?blocking=true&timeout=100") ~> Route.seal(routes(creds)) ~> check {
        status shouldBe Accepted
      }

      // repeat this time wait longer than active ack delay
      Post(s"$collectionPath/${action.name}?blocking=true&timeout=500") ~> Route.seal(routes(creds)) ~> check {
        status shouldBe OK
        val response = responseAs[JsObject]
        response shouldBe activation.withoutLogs.toExtendedJson
      }
    } finally {
      loadBalancer.whiskActivationStub = None
    }
  }

  it should "invoke a blocking action and return error response when activation fails" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val activation = WhiskActivation(
      action.namespace,
      action.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now,
      response = ActivationResponse.whiskError("test"))
    put(entityStore, action)
    // storing the activation in the db will allow the db polling to retrieve it
    // the test harness makes sure the activaiton id observed by the test matches
    // the one generated by the api handler
    put(activationStore, activation)
    try {
      Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(InternalServerError)
        val response = responseAs[JsObject]
        response should be(activation.withoutLogs.toExtendedJson)
      }
    } finally {
      deleteActivation(activation.docid)
    }
  }

  it should "ensure WhiskActionMetadata is used to invoke an action" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    put(entityStore, action)
    Post(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Accepted)
      val response = responseAs[JsObject]
      response.fields("activationId") should not be None
    }
    stream.toString should include(s"[WhiskActionMetaData] [GET] serving from datastore: ${CacheKey(action)}")
    stream.reset()
  }

  it should "report proper error when record is corrupted on delete" in {
    implicit val tid = transid()
    val entity = BadEntity(namespace, aname())
    put(entityStore, entity)

    Delete(s"$collectionPath/${entity.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }

  it should "report proper error when record is corrupted on get" in {
    implicit val tid = transid()
    val entity = BadEntity(namespace, aname())
    put(entityStore, entity)

    Get(s"$collectionPath/${entity.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }

  it should "report proper error when record is corrupted on put" in {
    implicit val tid = transid()
    val entity = BadEntity(namespace, aname())
    put(entityStore, entity)

    val components = Vector(stringToFullyQualifiedName(s"$namespace/${entity.name}"))
    val content = WhiskActionPut(Some(sequence(components)))

    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }

  // get and delete allowed, create/update with deprecated exec not allowed, post/invoke not allowed
  it should "report proper error when runtime is deprecated" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), swift("??"))
    val okUpdate = WhiskActionPut(Some(swift3("_")))
    val badUpdate = WhiskActionPut(Some(swift("_")))

    Put(s"$collectionPath/${action.name}", WhiskActionPut(Some(action.exec))) ~> Route.seal(routes(creds)) ~> check {
      status shouldBe BadRequest
      responseAs[ErrorResponse].error shouldBe Messages.runtimeDeprecated(action.exec)
    }

    Put(s"$collectionPath/${action.name}?overwrite=true", WhiskActionPut(Some(action.exec))) ~> Route.seal(
      routes(creds)) ~> check {
      status shouldBe BadRequest
      responseAs[ErrorResponse].error shouldBe Messages.runtimeDeprecated(action.exec)
    }

    put(entityStore, action)

    Put(s"$collectionPath/${action.name}?overwrite=true", JsObject()) ~> Route.seal(routes(creds)) ~> check {
      status shouldBe BadRequest
      responseAs[ErrorResponse].error shouldBe Messages.runtimeDeprecated(action.exec)
    }

    Put(s"$collectionPath/${action.name}?overwrite=true", badUpdate) ~> Route.seal(routes(creds)) ~> check {
      status shouldBe BadRequest
      responseAs[ErrorResponse].error shouldBe Messages.runtimeDeprecated(action.exec)
    }

    Post(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status shouldBe BadRequest
      responseAs[ErrorResponse].error shouldBe Messages.runtimeDeprecated(action.exec)
    }

    Get(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status shouldBe OK
    }

    Delete(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status shouldBe OK
    }

    put(entityStore, action)

    Put(s"$collectionPath/${action.name}?overwrite=true", okUpdate) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status shouldBe OK
    }
  }
}
