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

package org.apache.openwhisk.core.controller.test.migration

import scala.Vector

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.WskTestHelpers

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import spray.json.DefaultJsonProtocol._
import spray.json._

import org.apache.openwhisk.core.controller.WhiskActionsApi
import org.apache.openwhisk.core.controller.test.ControllerTestCommon
import org.apache.openwhisk.core.controller.test.WhiskAuthHelpers
import org.apache.openwhisk.core.entity._

/**
 * Tests migration of a new implementation of sequences: old style sequences can be updated and retrieved - standalone tests
 */
@RunWith(classOf[JUnitRunner])
class SequenceActionApiMigrationTests
    extends ControllerTestCommon
    with WhiskActionsApi
    with TestHelpers
    with WskTestHelpers {

  behavior of "Sequence Action API Migration"

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("seq_migration_tests")

  private def seqParameters(seq: Vector[String]) = Parameters("_actions", seq.toJson)

  it should "list old-style sequence action with explicit namespace" in {
    implicit val tid = transid()
    val components = Vector("/_/a", "/_/x/b", "/n/a", "/n/x/c").map(stringToFullyQualifiedName(_))
    val actions = (1 to 2).map { i =>
      WhiskAction(namespace, aname(), sequence(components))
    }.toList
    actions foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskAction, namespace, 2)
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      actions.length should be(response.length)
      response should contain theSameElementsAs actions.map(_.summaryAsJson)
    }
  }

  it should "get old-style sequence action by name in default namespace" in {
    implicit val tid = transid()
    val components = Vector("/_/a", "/_/x/b", "/n/a", "/n/x/c").map(stringToFullyQualifiedName(_))
    val action = WhiskAction(namespace, aname(), sequence(components))
    put(entityStore, action)
    Get(s"$collectionPath/${action.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskAction]
      response should be(action)
    }
  }

  // this test is a repeat from ActionsApiTest BUT with old style sequence
  it should "preserve new parameters when changing old-style sequence action to non sequence" in {
    implicit val tid = transid()
    val components = Vector("/_/a", "/_/x/b", "/n/a", "/n/x/c")
    val seqComponents = components.map(stringToFullyQualifiedName(_))
    val action = WhiskAction(namespace, aname(), sequence(seqComponents), seqParameters(components))
    val content = WhiskActionPut(Some(jsDefault("")), parameters = Some(Parameters("a", "A")))
    put(entityStore, action, false)

    // create an action sequence
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response.exec.kind should be(NODEJS10)
      response.parameters should be(Parameters("a", "A"))
    }
  }

  // this test is a repeat from ActionsApiTest BUT with old style sequence
  it should "reset parameters when changing old-style sequence action to non sequence" in {
    implicit val tid = transid()
    val components = Vector("/_/a", "/_/x/b", "/n/a", "/n/x/c")
    val seqComponents = components.map(stringToFullyQualifiedName(_))
    val action = WhiskAction(namespace, aname(), sequence(seqComponents), seqParameters(components))
    val content = WhiskActionPut(Some(jsDefault("")))
    put(entityStore, action, false)

    // create an action sequence
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[WhiskAction]
      response.exec.kind should be(NODEJS10)
      response.parameters shouldBe Parameters()
    }
  }

  it should "update old-style sequence action with new annotations" in {
    implicit val tid = transid()
    val components = Vector("/_/a", "/_/x/b", "/n/a", "/n/x/c")
    val seqComponents = components.map(stringToFullyQualifiedName(_))
    val action = WhiskAction(namespace, aname(), sequence(seqComponents))
    val content = """{"annotations":[{"key":"old","value":"new"}]}""".parseJson.asJsObject
    put(entityStore, action, false)

    // create an action sequence
    Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(action.docid)
      status should be(OK)
      val response = responseAs[String]
      // contains the action
      components map { c =>
        response should include(c)
      }
      // contains the annotations
      response should include("old")
      response should include("new")
    }
  }

  it should "update an old-style sequence with new sequence" in {
    implicit val tid = transid()
    // old sequence
    val seqName = EntityName(s"${aname()}_new")
    val oldComponents = Vector("/_/a", "/_/x/b", "/n/a", "/n/x/c").map(stringToFullyQualifiedName(_))
    val oldSequence = WhiskAction(namespace, seqName, sequence(oldComponents))
    put(entityStore, oldSequence)

    // new sequence
    val limit = 5 // count of bogus actions in sequence
    val bogus = s"${aname()}_bogus"
    val bogusActionName = s"/_/${bogus}" // test that default namespace gets properly replaced
    // put the action in the entity store so it exists
    val bogusAction = WhiskAction(namespace, EntityName(bogus), jsDefault("??"), Parameters("x", "y"))
    put(entityStore, bogusAction)
    val seqComponents = for (i <- 1 to limit) yield stringToFullyQualifiedName(bogusActionName)
    val seqAction = WhiskAction(namespace, seqName, sequence(seqComponents.toVector))
    val content = WhiskActionPut(Some(seqAction.exec), Some(Parameters()))

    // update an action sequence
    Put(s"$collectionPath/${seqName}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteAction(seqAction.docid)
      status should be(OK)

      val response = responseAs[WhiskAction]
      response.exec.kind should be(Exec.SEQUENCE)
      response.limits should be(seqAction.limits)
      response.publish should be(seqAction.publish)
      response.version should be(seqAction.version.upPatch)
    }
  }
}
