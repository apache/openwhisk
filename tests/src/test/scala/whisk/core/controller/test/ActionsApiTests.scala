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
import whisk.core.entitlement.Collection
import whisk.http.ErrorResponse
import whisk.http.Messages
import java.io.ByteArrayInputStream
import java.util.Base64

import akka.stream.scaladsl._

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
  it should "return empty list when no actions exist" in {
    implicit val tid = transid()
    Get(collectionPath) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      responseAs[List[JsObject]] shouldBe 'empty
    }
  }

  it should "list actions by default namespace" in {
    implicit val tid = transid()
    val actions = (1 to 2).map { i =>
      WhiskAction(namespace, aname(), jsDefault("??"), Parameters("x", "b"))
    }.toList
    actions foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskAction, namespace, 2)
    Get(collectionPath) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      actions.length should be(response.length)
      response should contain theSameElementsAs actions.map(_.summaryAsJson)
    }
  }

  it should "reject list when limit is greater than maximum allowed value" in {
    implicit val tid = transid()
    val exceededMaxLimit = Collection.MAX_LIST_LIMIT + 1
    val response = Get(s"$collectionPath?limit=$exceededMaxLimit") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listLimitOutOfRange(Collection.ACTIONS, exceededMaxLimit, Collection.MAX_LIST_LIMIT)
      }
    }
  }

  it should "reject list when limit is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?limit=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.ACTIONS, notAnInteger)
      }
    }
  }

  it should "reject list when skip is negative" in {
    implicit val tid = transid()
    val negativeSkip = -1
    val response = Get(s"$collectionPath?skip=$negativeSkip") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listSkipOutOfRange(Collection.ACTIONS, negativeSkip)
      }
    }
  }

  it should "reject list when skip is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?skip=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.ACTIONS, notAnInteger)
      }
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
      response should contain theSameElementsAs actions.map(_.summaryAsJson)
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
      response should contain theSameElementsAs actions.map(_.summaryAsJson)
    }

    // it should "reject list action with explicit namespace not owned by subject" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "list should reject request with post" in {
    implicit val tid = transid()
    Post(collectionPath) ~> Route.seal(routes(creds)) ~> check {
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

  def getExecPermutations() = {
    implicit val tid = transid()

    // BlackBox: binary: true, main: bbMain
    val bbAction1 = WhiskAction(namespace, aname(), bb("bb", "RHViZWU=", Some("bbMain")))
    val bbAction1Content = Map("exec" -> Map(
      "kind" -> Exec.BLACKBOX,
      "code" -> "RHViZWU=",
      "image" -> "bb",
      "main" -> "bbMain")).toJson.asJsObject
    val bbAction1ExecMetaData = blackBoxMetaData("bb", Some("bbMain"), true)

    // BlackBox: binary: false, main: bbMain
    val bbAction2 = WhiskAction(namespace, aname(), bb("bb", "", Some("bbMain")))
    val bbAction2Content =
      Map("exec" -> Map("kind" -> Exec.BLACKBOX, "code" -> "", "image" -> "bb", "main" -> "bbMain")).toJson.asJsObject
    val bbAction2ExecMetaData = blackBoxMetaData("bb", Some("bbMain"), false)

    // BlackBox: binary: true, no main
    val bbAction3 = WhiskAction(namespace, aname(), bb("bb", "RHViZWU="))
    val bbAction3Content =
      Map("exec" -> Map("kind" -> Exec.BLACKBOX, "code" -> "RHViZWU=", "image" -> "bb")).toJson.asJsObject
    val bbAction3ExecMetaData = blackBoxMetaData("bb", None, true)

    // BlackBox: binary: false, no main
    val bbAction4 = WhiskAction(namespace, aname(), bb("bb", ""))
    val bbAction4Content = Map("exec" -> Map("kind" -> Exec.BLACKBOX, "code" -> "", "image" -> "bb")).toJson.asJsObject
    val bbAction4ExecMetaData = blackBoxMetaData("bb", None, false)

    // Attachment: binary: true, main: javaMain
    val javaAction1 = WhiskAction(namespace, aname(), javaDefault("RHViZWU=", Some("javaMain")))
    val javaAction1Content =
      Map("exec" -> Map("kind" -> JAVA_DEFAULT, "code" -> "RHViZWU=", "main" -> "javaMain")).toJson.asJsObject
    val javaAction1ExecMetaData = javaMetaData(Some("javaMain"), true)

    // String: binary: true, main: jsMain
    val jsAction1 = WhiskAction(namespace, aname(), jsDefault("RHViZWU=", Some("jsMain")))
    val jsAction1Content =
      Map("exec" -> Map("kind" -> NODEJS6, "code" -> "RHViZWU=", "main" -> "jsMain")).toJson.asJsObject
    val jsAction1ExecMetaData = js6MetaData(Some("jsMain"), true)

    // String: binary: false, main: jsMain
    val jsAction2 = WhiskAction(namespace, aname(), jsDefault("", Some("jsMain")))
    val jsAction2Content = Map("exec" -> Map("kind" -> NODEJS6, "code" -> "", "main" -> "jsMain")).toJson.asJsObject
    val jsAction2ExecMetaData = js6MetaData(Some("jsMain"), false)

    // String: binary: true, no main
    val jsAction3 = WhiskAction(namespace, aname(), jsDefault("RHViZWU="))
    val jsAction3Content = Map("exec" -> Map("kind" -> NODEJS6, "code" -> "RHViZWU=")).toJson.asJsObject
    val jsAction3ExecMetaData = js6MetaData(None, true)

    // String: binary: false, no main
    val jsAction4 = WhiskAction(namespace, aname(), jsDefault(""))
    val jsAction4Content = Map("exec" -> Map("kind" -> NODEJS6, "code" -> "")).toJson.asJsObject
    val jsAction4ExecMetaData = js6MetaData(None, false)

    // Sequence
    val component = WhiskAction(namespace, aname(), jsDefault("??"))
    put(entityStore, component)
    val components = Vector(s"/$namespace/${component.name}").map(stringToFullyQualifiedName(_))
    val seqAction = WhiskAction(namespace, aname(), sequence(components), seqParameters(components))
    val seqActionContent = JsObject(
      "exec" -> JsObject("kind" -> "sequence".toJson, "components" -> JsArray(s"/$namespace/${component.name}".toJson)))
    val seqActionExecMetaData = sequenceMetaData(components)

    Seq(
      (bbAction1, bbAction1Content, bbAction1ExecMetaData),
      (bbAction2, bbAction2Content, bbAction2ExecMetaData),
      (bbAction3, bbAction3Content, bbAction3ExecMetaData),
      (bbAction4, bbAction4Content, bbAction4ExecMetaData),
      (javaAction1, javaAction1Content, javaAction1ExecMetaData),
      (jsAction1, jsAction1Content, jsAction1ExecMetaData),
      (jsAction2, jsAction2Content, jsAction2ExecMetaData),
      (jsAction3, jsAction3Content, jsAction3ExecMetaData),
      (jsAction4, jsAction4Content, jsAction4ExecMetaData),
      (seqAction, seqActionContent, seqActionExecMetaData))
  }

  it should "get action using code query parameter" in {
    implicit val tid = transid()

    getExecPermutations.foreach {
      case (action, content, execMetaData) =>
        val expectedWhiskAction = WhiskAction(
          action.namespace,
          action.name,
          action.exec,
          action.parameters,
          action.limits,
          action.version,
          action.publish,
          action.annotations ++ Parameters(WhiskAction.execFieldName, action.exec.kind))

        val expectedWhiskActionMetaData = WhiskActionMetaData(
          action.namespace,
          action.name,
          execMetaData,
          action.parameters,
          action.limits,
          action.version,
          action.publish,
          action.annotations ++ Parameters(WhiskActionMetaData.execFieldName, action.exec.kind))

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

  it should "reject exec with unknown or missing kind" in {
    implicit val tid = transid()
    Seq("", "foobar").foreach { kind =>
      val content = s"""{"exec":{"kind": "$kind", "code":"??"}}""".stripMargin.parseJson.asJsObject
      Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
        status should be(BadRequest)
        responseAs[String] should include {
          s"kind '$kind' not in Set"
        }
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
    val action = WhiskAction(namespace, aname(), jsDefault(""))
    // only a kind must be defined (code otherwise could be empty)
    val content = JsObject("exec" -> JsObject("code" -> "".toJson, "kind" -> action.exec.kind.toJson))
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

  it should "put and then get an action with attachment from cache" in {
    val action =
      WhiskAction(
        namespace,
        aname(),
        javaDefault(nonInlinedCode(entityStore), Some("hello")),
        annotations = Parameters("exec", "java"))
    val content = WhiskActionPut(
      Some(action.exec),
      Some(action.parameters),
      Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))
    val name = action.name
    val cacheKey = s"${CacheKey(action)}".replace("(", "\\(").replace(")", "\\)")
    val expectedPutLog =
      Seq(s"uploading attachment '[\\w-]+' of document 'id: ${action.namespace}/${action.name}", s"caching $cacheKey")
        .mkString("(?s).*")
    val notExpectedGetLog = Seq(
      s"finding document: 'id: ${action.namespace}/${action.name}",
      s"finding attachment '[\\w-/:]+' of document 'id: ${action.namespace}/${action.name}").mkString("(?s).*")

    // first request invalidates any previous entries and caches new result
    Put(s"$collectionPath/$name", content) ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }

    stream.toString should not include (s"invalidating ${CacheKey(action)} on delete")
    stream.toString should include regex (expectedPutLog)
    stream.reset()

    // second request should fetch from cache
    Get(s"$collectionPath/$name") ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }

    stream.toString should include(s"serving from cache: ${CacheKey(action)}")
    stream.toString should not include regex(notExpectedGetLog)
    stream.reset()

    // delete should invalidate cache
    Delete(s"$collectionPath/$name") ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }
    stream.toString should include(s"invalidating ${CacheKey(action)}")
    stream.reset()
  }

  it should "put and then get an action with inlined attachment" in {
    assumeAttachmentInliningEnabled(entityStore)
    val action =
      WhiskAction(
        namespace,
        aname(),
        javaDefault(encodedRandomBytes(inlinedAttachmentSize(entityStore)), Some("hello")),
        annotations = Parameters("exec", "java"))
    val content = WhiskActionPut(
      Some(action.exec),
      Some(action.parameters),
      Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))
    val name = action.name
    val cacheKey = s"${CacheKey(action)}".replace("(", "\\(").replace(")", "\\)")
    val notExpectedGetLog = Seq(
      s"finding document: 'id: ${action.namespace}/${action.name}",
      s"finding attachment '[\\w-/:]+' of document 'id: ${action.namespace}/${action.name}").mkString("(?s).*")

    // first request invalidates any previous entries and caches new result
    Put(s"$collectionPath/$name", content) ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }

    stream.toString should not include (s"invalidating ${CacheKey(action)} on delete")
    stream.toString should not include ("uploading attachment")
    stream.reset()

    // second request should fetch from cache
    Get(s"$collectionPath/$name") ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }

    stream.toString should include(s"serving from cache: ${CacheKey(action)}")
    stream.toString should not include regex(notExpectedGetLog)
    stream.reset()

    // delete should invalidate cache
    Delete(s"$collectionPath/$name") ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }
    stream.toString should include(s"invalidating ${CacheKey(action)}")
    stream.reset()
  }

  it should "get an action with attachment that is not cached" in {
    implicit val tid = transid()
    val code = nonInlinedCode(entityStore)
    val action =
      WhiskAction(namespace, aname(), javaDefault(code, Some("hello")), annotations = Parameters("exec", "java"))
    val content = WhiskActionPut(
      Some(action.exec),
      Some(action.parameters),
      Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))
    val name = action.name
    val cacheKey = s"${CacheKey(action)}".replace("(", "\\(").replace(")", "\\)")
    val expectedGetLog = Seq(
      s"finding document: 'id: ${action.namespace}/${action.name}",
      s"finding attachment '[\\w-/:]+' of document 'id: ${action.namespace}/${action.name}").mkString("(?s).*")

    action.exec match {
      case exec @ CodeExecAsAttachment(_, _, _) =>
        val stream = new ByteArrayInputStream(Base64.getDecoder().decode(code))
        val manifest = exec.manifest.attached.get
        val src = StreamConverters.fromInputStream(() => stream)
        putAndAttach[WhiskAction, WhiskEntity](
          entityStore,
          action,
          (d, a) => d.copy(exec = exec.attach(a)).revision[WhiskAction](d.rev),
          manifest.attachmentType,
          src,
          None)

      case _ =>
    }

    // second request should fetch from cache
    Get(s"$collectionPath/$name") ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }

    stream.toString should include regex (expectedGetLog)
    stream.reset()
  }

  it should "update an existing action with attachment that is not cached" in {
    implicit val tid = transid()
    val code = nonInlinedCode(entityStore)
    val action =
      WhiskAction(namespace, aname(), javaDefault(code, Some("hello")), annotations = Parameters("exec", "java"))
    val content = WhiskActionPut(
      Some(action.exec),
      Some(action.parameters),
      Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))
    val name = action.name
    val cacheKey = s"${CacheKey(action)}".replace("(", "\\(").replace(")", "\\)")
    val expectedPutLog =
      Seq(s"uploading attachment '[\\w-/:]+' of document 'id: ${action.namespace}/${action.name}", s"caching $cacheKey")
        .mkString("(?s).*")

    action.exec match {
      case exec @ CodeExecAsAttachment(_, _, _) =>
        val stream = new ByteArrayInputStream(Base64.getDecoder().decode(code))
        val manifest = exec.manifest.attached.get
        val src = StreamConverters.fromInputStream(() => stream)
        putAndAttach[WhiskAction, WhiskEntity](
          entityStore,
          action,
          (d, a) => d.copy(exec = exec.attach(a)).revision[WhiskAction](d.rev),
          manifest.attachmentType,
          src,
          None)

      case _ =>
    }

    Put(s"$collectionPath/$name?overwrite=true", content) ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }
    stream.toString should include regex (expectedPutLog)
    stream.reset()

    // delete should invalidate cache
    Delete(s"$collectionPath/$name") ~> Route.seal(routes(creds)(transid())) ~> check {
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
          action.annotations ++ Parameters(WhiskAction.execFieldName, JAVA_DEFAULT)))
    }
    stream.toString should include(s"invalidating ${CacheKey(action)}")
    stream.reset()
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

  it should "not invoke an action when final parameters are redefined" in {
    implicit val tid = transid()
    val annotations = Parameters(WhiskActionMetaData.finalParamsAnnotationName, JsBoolean(true))
    val parameters = Parameters("a", "A") ++ Parameters("empty", JsNull)
    val action = WhiskAction(namespace, aname(), jsDefault("??"), parameters = parameters, annotations = annotations)
    put(entityStore, action)
    Seq((Parameters("a", "B"), BadRequest), (Parameters("empty", "C"), Accepted)).foreach {
      case (p, code) =>
        Post(s"$collectionPath/${action.name}", p.toJsObject) ~> Route.seal(routes(creds)) ~> check {
          status should be(code)
          if (code == BadRequest) {
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
          }
        }
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
    storeActivation(activation)
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
      deleteActivation(ActivationId(activation.docid.asString))
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
    // the test harness makes sure the activation id observed by the test matches
    // the one generated by the api handler
    storeActivation(activation)
    try {
      Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(InternalServerError)
        val response = responseAs[JsObject]
        response should be(activation.withoutLogs.toExtendedJson)
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString))
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

    Put(s"$collectionPath/${action.name}?overwrite=true", JsObject.empty) ~> Route.seal(routes(creds)) ~> check {
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
