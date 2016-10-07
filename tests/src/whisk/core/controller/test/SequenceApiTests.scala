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

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import java.io.PrintStream

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.common.TransactionId
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
import whisk.http.Messages._

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
    val defaultNamespace = wskprops.namespace
    def aname = MakeName.next("sequence_tests")
    val allowedActionDuration = 120 seconds

    it should "reject creation of sequence with more actions than allowed limit" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_toomanyactions")
        val limit = 11 // make this at greater than the current allowed limit
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        // put the action in the entity store so it's found
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield stringToFullyQualifiedName(bogusAction)
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence.toVector))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(sequenceIsTooLong)
        }
    }

    it should "reject creation of sequence with non-existent action" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_toomanyactions")
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        val sequence = Vector(bogusAction).map(stringToFullyQualifiedName(_))
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(sequenceComponentNotFound)
        }
    }

    it should "reject update of sequence with cycle" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_cycle")
        val limit = 3 // make this less than the current allowed limit
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        // put the action in the entity store so it's found
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield stringToFullyQualifiedName(bogusAction)
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence.toVector))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

        val seqNameWithNamespace = stringToFullyQualifiedName(s"/${namespace}/${seqName.name}")
        // update the action sequence
        val updatedSeq = sequence.updated(1, seqNameWithNamespace)
        val updatedSeqAction = WhiskAction(namespace, seqName, Exec.sequence(updatedSeq.toVector))
        val updatedContent = WhiskActionPut(Some(updatedSeqAction.exec))

        // update the sequence
        Put(s"${collectionPath}/${seqName.name}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(sequenceIsCyclic)
        }
    }

    it should "allow creation of sequence provided the number of actions is less than allowed limit" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_normal")
        val limit = 5 // count of bogus actions in sequence
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${defaultNamespace}/${bogus}"   // test that default namespace gets properly replaced
        // put the action in the entity store so it exists
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield stringToFullyQualifiedName(bogusAction)
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
        val sequence = Vector(s"/${defaultNamespace}/${pkgWithBinding}/${bogus}").map(stringToFullyQualifiedName(_))
        val seqAction = WhiskAction(namespace, seqName, Exec.sequence(sequence))
        val content = WhiskActionPut(Some(seqAction.exec))

        // create an action sequence
        Put(s"${collectionPath}/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[String]
        }
    }

    it should "reject update of sequence with cycle through bindings" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname}_cycle_binding")
        val limit = 3 // make this less than the current allowed limit
        val bogus = s"${aname}_bogus"
        val bogusAction = s"/${namespace}/${bogus}"
        // put the action in the entity store so it's found
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"), Parameters("x", "y"))
        put(entityStore, bogusAct)
        val sequence = for (i <- 1 to limit) yield stringToFullyQualifiedName(bogusAction)
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
        val seqNameWithBinding = stringToFullyQualifiedName(s"/${namespace}/${pkgWithBinding}/${seqName.name}")  // the same as previous sequence through package binding; should detect cycle
        // update the action sequence
        val updatedSeq = sequence.updated(1, seqNameWithBinding)
        val updatedSeqAction = WhiskAction(namespace, seqName, Exec.sequence(updatedSeq.toVector))
        val updatedContent = WhiskActionPut(Some(updatedSeqAction.exec))

        // update the sequence
        Put(s"${collectionPath}/$pkg/${seqName.name}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(sequenceIsCyclic)
        }
    }

    it should "reject creation of a sequence with components that don't have at least namespace and action name" in {
        implicit val tid = transid()
        val content = """{"exec":{"kind":"sequence","code":"","components":["a","b"]}}""".parseJson.asJsObject

        // create an action sequence
        Put(s"$collectionPath/${aname}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject update of a sequence with components that don't have at least namespace and action name" in {
        implicit val tid = transid()
        val bogus = s"${aname}_bogus"
        val bogusAct = WhiskAction(namespace, EntityName(bogus), Exec.js("??"))
        // put the action in the entity store so it's found
        put(entityStore, bogusAct)

        val updatedContent = """{"exec":{"kind":"sequence","code":"","components":["a","b"]}}""".parseJson.asJsObject

        // update an action sequence
        Put(s"$collectionPath/${bogus}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(bogusAct.docid)
            status should be(BadRequest)
            response.entity.toString should include(malformedFullyQualifiedEntityName)
        }
    }

    it should "create a sequence of type s -> x, x where x is a sequence and correctly count the atomic actions" in {
        implicit val tid = transid()
        val actionCnt = 4
        val bogus = s"${aname}_bogus"
        val components = for (i <- 1 to actionCnt) yield bogus
        val xName = s"${aname}_x"
        // make sequence x and install it in db
        putSimpleSequenceInDB(xName, namespace, components.toVector)
        val sName = s"${aname}_s"
        val sSeq = makeSimpleSequence(sName, namespace, Vector(s"$xName", s"$xName"), false) // x is install in the db already
        // create an action sequence
        val content = WhiskActionPut(Some(sSeq.exec))

        implicit val stream = new java.io.ByteArrayOutputStream
        this.outputStream = new PrintStream(stream)

        try {
            stream.reset()
            Console.withOut(stream) {
                Put(s"${collectionPath}/$sName", content) ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                }
                logContains(s"atomic action count ${2 * actionCnt}")
            }
        } finally {
            stream.close()
            this.outputStream.close()
        }
    }

    /**
     * Tests the following sequence:
     * y -> a
     * x -> b, z
     * s -> a, x, y
     *
     * Update z -> s should not work
     * Update s -> a, s, b should not work
     * Update z -> y should work (no cycle) act cnt 1
     * Update s -> a, x, y, a, b should work (no cycle) act cnt 6
     */
    it should "create a complex sequence, allow updates with no cycle and reject updates with cycle" in {
        implicit val tid = transid()
        val actionCnt = 4
        val aAct = s"${aname}_a"
        val yAct = s"${aname}_y"
        val yComp = Vector(aAct)
        // make seq y and store it in the db
        putSimpleSequenceInDB(yAct, namespace, yComp)
        val bAct = s"${aname}_b"
        val zAct = s"${aname}_z"
        val xAct = s"${aname}_x"
        val xComp = Vector(bAct, zAct)
        // make sequence x and install it in db
        putSimpleSequenceInDB(xAct, namespace, xComp)
        val sAct = s"${aname}_s"
        val sSeq = makeSimpleSequence(sAct, namespace, Vector(s"$aAct", s"$xAct", s"$yAct"), false) // a, x, y  in the db already
        // create an action sequence s
        val content = WhiskActionPut(Some(sSeq.exec))

        implicit val stream = new java.io.ByteArrayOutputStream
        this.outputStream = new PrintStream(stream)
        try {
            stream.reset()
            Console.withOut(stream) {
                Put(s"${collectionPath}/$sAct", content) ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                }
                logContains("atomic action count 4")
            }

            // update action z to point to s --- should be rejected
            val zUpdate = makeSimpleSequence(zAct, namespace, Vector(s"$sAct"), false) // s in the db already
            val zUpdateContent = WhiskActionPut(Some(zUpdate.exec))
            Put(s"${collectionPath}/$zAct?overwrite=true", zUpdateContent) ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                response.entity.toString should include(sequenceIsCyclic)
            }

            // update action s to point to a, s, b --- should be rejected
            val sUpdate = makeSimpleSequence(sAct, namespace, Vector(s"$aAct", s"$sAct", s"$bAct"), false) // s in the db already
            val sUpdateContent = WhiskActionPut(Some(sUpdate.exec))
            Put(s"${collectionPath}/$sAct?overwrite=true", sUpdateContent) ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                response.entity.toString should include(sequenceIsCyclic)
            }

            // update action z to point to y
            val zSeq = makeSimpleSequence(zAct, namespace, Vector(s"$yAct"), false) // y  in the db already
            val updateContent = WhiskActionPut(Some(zSeq.exec))
            stream.reset()
            Console.withOut(stream) {
                Put(s"${collectionPath}/$zAct?overwrite=true", updateContent) ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                }
                logContains("atomic action count 1")
            }
            // update sequence s to s -> a, x, y, a, b
            val newS = makeSimpleSequence(sAct, namespace, Vector(s"$aAct", s"$xAct", s"$yAct", s"$aAct", s"$bAct"), false) // a, x, y, b  in the db already
            val newSContent = WhiskActionPut(Some(newS.exec))
            stream.reset()
            Console.withOut(stream) {
                Put(s"${collectionPath}/$sAct?overwrite=true", newSContent) ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                }
                logContains("atomic action count 6")
            }
        } finally {
            stream.close()
            this.outputStream.close()
        }
    }

    it should "reject the creation of a sequence that points to itself" in {
        implicit val tid = transid()
        val bogusAct = s"${aname}_bogus"
        val bogusWskAct = WhiskAction(namespace, EntityName(bogusAct), Exec.js("??"))
        // put the action in the entity store so it's found
        put(entityStore, bogusWskAct)

        val sAct = s"${aname}_s"
        val sSeq = makeSimpleSequence(sAct, namespace, Vector(s"$bogusAct", s"$sAct", s"$bogusAct"), false)
        // create an action sequence s
        val content = WhiskActionPut(Some(sSeq.exec))
        Put(s"${collectionPath}/$sAct", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            response.entity.toString should include(sequenceIsCyclic)
        }
    }

    /**
     * Makes a simple sequence action and installs it in the db (no call to wsk api/cli).
     * All actions are in the default package.
     *
     * @param sequenceName the name of the sequence
     * @param ns the namespace to be used when creating the component actions and the sequence action
     * @param components the names of the actions (entity names, no namespace)
     */
    private def putSimpleSequenceInDB(sequenceName: String, ns: EntityPath, components: Vector[String])(
        implicit tid: TransactionId) = {
        val seqAction = makeSimpleSequence(sequenceName, ns, components)
        put(entityStore, seqAction)
    }
    /**
     * Returns a WhiskAction that can be used to create/update a sequence.
     * If instructed to do so, installs the component actions in the db.
     * All actions are in the default package.
     *
     * @param sequenceName the name of the sequence
     * @param ns the namespace to be used when creating the component actions and the sequence action
     * @param componentNames the names of the actions (entity names, no namespace)
     * @param installDB if true, installs the component actions in the db (default true)
     */
    private def makeSimpleSequence(sequenceName: String, ns: EntityPath, componentNames: Vector[String], installDB: Boolean = true)(
        implicit tid: TransactionId): WhiskAction = {
        if (installDB) {
            // create bogus wsk actions
            val wskActions = componentNames.toSet[String] map { c => WhiskAction(ns, EntityName(c), Exec.js("??")) }
            // add them to the db
            wskActions.foreach { put(entityStore, _) }
        }
        // add namespace to component names
        val components = componentNames map { c => s"/$ns/$c" }
        // create wsk action for the sequence
        val fqenComponents = components map { c => stringToFullyQualifiedName(c) }
        WhiskAction(namespace, EntityName(sequenceName), Exec.sequence(fqenComponents))
    }

    private def logContains(w: String)(implicit stream: java.io.ByteArrayOutputStream): Boolean = {
        whisk.utils.retry({
            val log = stream.toString()
            val result = log.contains(w)
            assert(result) // throws exception required to retry
            result
        }, 10, Some(100 milliseconds))
    }
}
