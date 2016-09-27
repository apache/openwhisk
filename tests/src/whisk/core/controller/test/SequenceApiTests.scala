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

package whisk.core.controller.test

import common.TestHelpers
import common.Wsk
import common.WskProps
import common.WskTestHelpers

import whisk.core.controller.WhiskActionsApi
import whisk.core.entity.AuthKey
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.Exec
import whisk.core.entity.Parameters
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActionPut
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskPackage

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import spray.json.pimpString
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat


import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller

/**
 * Tests Sequence API - stand-alone tests that require only the controller to be up
 */
@RunWith(classOf[JUnitRunner])
class SequenceApiTests
    extends ControllerTestCommon
    with WhiskActionsApi
    with TestHelpers
    with WskTestHelpers {

    behavior of "Sequence API"

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject())
    val guestNamespace = wskprops.namespace
    def aname = MakeName.next("sequence_tests")
    val allowedActionDuration = 120 seconds

    private val tooManyActionsMessage = "too many actions in sequence"
    private val recursionDetectedMessage = "recursion detected in sequence"
    private val actionNotFoundMessage = "action not found"
    private val malformedActionNameMessage = "fully qualified entity name must contain at least the namespace and the name of the entity"

    it should "reject creation of sequence with more actions than allowed limit" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_toomanyactions")
        val limit = 11 // make this at greater than the current allowed limit
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        // put the action in the entity store so it's found
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield bogusAction
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence.toVector))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(tooManyActionsMessage)
        }
    }

    it should "reject creation of sequence with non-existent action" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_toomanyactions")
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        val sequence = Vector(bogusAction)
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(actionNotFoundMessage)
        }
    }

    it should "reject update of sequence with recursion" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_recursion")
        val limit = 3 // make this less than the current allowed limit
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        // put the action in the entity store so it's found
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield bogusAction
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence.toVector))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

        val seqNameWithNamespace = s"/${namespace}/${seqName.name}"
        // update the action sequence
        val updatedSeq = sequence.updated(1, seqNameWithNamespace)
        val updatedSeqAction = WhiskAction(namespace, seqName, Exec.sequence(updatedSeq.toVector))
        val updatedContent = WhiskActionPut(Some(updatedSeqAction.exec))

        // update the sequence
        Put(s"${collectionPath}/${seqName.name}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(recursionDetectedMessage)
        }
    }

    it should "allow creation of sequence provided the number of actions is less than allowed limit" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_normal")
        val limit = 5 // count of bogus actions in sequence
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${guestNamespace}/${bogus}"   // test that default namespace gets properly replaced
        // put the action in the entity store so it exists
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield bogusAction
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence.toVector))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "allow creation of sequence with actions with package bindings" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_withbindings")
        val bogus = s"${aname}_bogus"
        val pkg = s"${aname}_pkg"
        val wp = WhiskPackage(namespace, EntityName(pkg), None, publish = true)
        // put the package in the entity store
        put(entityStore, wp)
        // create binding to wp
        val pkgWithBinding = s"${aname}_pkgbinding"
        val wpBinding = WhiskPackage(namespace, EntityName(pkgWithBinding), wp.bind, publish = true)
        put(entityStore, wpBinding)
        val namespaceWithPkg = s"/${namespace}/${pkg}"
        // put the action in the entity store so it exists
        val bogusAct = WhiskAction(EntityPath(namespaceWithPkg), EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        // create sequence that refers to action with binding
        val sequence = Vector(s"/${guestNamespace}/${pkgWithBinding}/${bogus}")
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[String]
        }
    }

    it should "reject update of sequence with recursion through bindings" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_recursion_binding")
        val limit = 3 // make this less than the current allowed limit
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        // put the action in the entity store so it's found
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield bogusAction
        // create package
        val pkg = s"${aname}_pkg"
        val wp = WhiskPackage(namespace, EntityName(pkg), None, publish = true)
        // put the package in the entity store
        put(entityStore, wp)
        val namespaceWithPkg = EntityPath(s"/$namespace/$pkg")
        val seqAction = WhiskAction(namespaceWithPkg, seqName, Exec.sequence(sequence.toVector))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/$pkg/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

        // create binding
        val pkgWithBinding = s"${aname}_pkgbinding"
        val wpBinding = WhiskPackage(namespace, EntityName(pkgWithBinding), wp.bind, publish = true)
        put(entityStore, wpBinding)
        val seqNameWithBinding = s"/${namespace}/${pkgWithBinding}/${seqName.name}"  // the same as previous sequence through package binding; should detect recursion
        // update the action sequence
        val updatedSeq = sequence.updated(1, seqNameWithBinding)
        val updatedSeqAction = WhiskAction(namespace, seqName, Exec.sequence(updatedSeq.toVector))
        val updatedContent = WhiskActionPut(Some(updatedSeqAction.exec))

        // update the sequence
        Put(s"${collectionPath}/$pkg/${seqName.name}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(recursionDetectedMessage)
        }
    }

    it should "reject creation of a sequence with components that don't have at least namespace and action name" in {
        implicit val tid = transid()
        val sequence = Vector("a", "b")
        //val action = WhiskAction(namespace, aname, Exec.sequence(sequence))
        val content = """{"exec":{"kind":"sequence","code":"","components":["a","b"]}}""".parseJson.asJsObject

        // create an action sequence
        Put(s"$collectionPath/${aname}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject update of a sequence with components that don't have at least namespace and action name" in {
        implicit val tid = transid()
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        // put the action in the entity store so it's found
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"))
        put(entityStore, bogusAct)

        val sequence = Vector("a", "b")
        val updatedContent = """{"exec":{"kind":"sequence","code":"","components":["a","b"]}}""".parseJson.asJsObject

        // create an action sequence
        Put(s"$collectionPath/${bogus}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(bogusAct.docid)
            status should be(BadRequest)
            response.entity.toString should include(malformedActionNameMessage)
        }
    }
}
