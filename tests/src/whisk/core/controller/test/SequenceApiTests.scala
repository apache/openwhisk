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

import java.io.PrintStream

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.event.Logging.DebugLevel
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.controller.WhiskActionsApi
import whisk.core.entity._
import whisk.http.ErrorResponse
import whisk.http.Messages

/**
 * Tests Sequence API - stand-alone tests that require only the controller to be up
 */
@RunWith(classOf[JUnitRunner])
class SequenceApiTests
    extends ControllerTestCommon
    with WhiskActionsApi {

    behavior of "Sequence API"

    val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject())
    val defaultNamespace = EntityPath.DEFAULT
    def aname() = MakeName.next("sequence_tests")
    val allowedActionDuration = 120 seconds

    // set logging level to debug
    setVerbosity(DebugLevel)

    it should "reject creation of sequence with more actions than allowed limit" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname()}_toomanyactions")
        // put the component action in the entity store so it's found
        val component = WhiskAction(namespace, aname(), Exec.js("??"))
        put(entityStore, component)
        // create exec sequence that will violate max length
        val limit = whiskConfig.actionSequenceLimit.toInt + 1 // one more than allowed
        val sequence = for (i <- 1 to limit) yield stringToFullyQualifiedName(component.docid())
        val content = WhiskActionPut(Some(Exec.sequence(sequence.toVector)))

        // create an action sequence
        Put(s"$collectionPath/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.sequenceIsTooLong
        }
    }

    it should "reject creation of sequence with non-existent action" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname()}_componentnotfound")
        val bogus = s"${aname()}_bogus"
        val bogusAction = s"/$namespace/$bogus"
        val sequence = Vector(bogusAction).map(stringToFullyQualifiedName(_))
        val content = WhiskActionPut(Some(Exec.sequence(sequence)))

        // create an action sequence
        Put(s"$collectionPath/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.sequenceComponentNotFound
        }
    }

    it should "reject create sequence that points to itself" in {
        implicit val tid = transid()
        val seqName = s"${aname()}_cyclic"
        val sSeq = makeSimpleSequence(seqName, namespace, Vector(seqName), false)

        // create an action sequence
        val content = WhiskActionPut(Some(sSeq.exec))
        Put(s"$collectionPath/$seqName", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.sequenceIsCyclic
        }
    }

    it should "reject create sequence that points to itself with many components" in {
        implicit val tid = transid()

        // put the action in the entity store so it's found
        val component = WhiskAction(namespace, aname(), Exec.js("??"))
        put(entityStore, component)

        val seqName = s"${aname()}_cyclic"
        val sSeq = makeSimpleSequence(seqName, namespace, Vector(component.name(), seqName, component.name()), false)

        // create an action sequence
        val content = WhiskActionPut(Some(sSeq.exec))
        Put(s"$collectionPath/$seqName", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.sequenceIsCyclic
        }
    }

    it should "reject update of sequence with cycle" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname()}_cycle")
        // put the component action in the entity store so it's found
        val component = WhiskAction(namespace, aname(), Exec.js("??"))
        put(entityStore, component)
        // create valid exec sequence initially
        val sequence = for (i <- 1 to 2) yield stringToFullyQualifiedName(component.docid())
        val content = WhiskActionPut(Some(Exec.sequence(sequence.toVector)))

        // create a valid action sequence first
        Put(s"$collectionPath/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

        // now create exec sequence with a self-reference
        val seqNameWithNamespace = stringToFullyQualifiedName(s"/${namespace}/${seqName.name}")
        val updatedSeq = sequence.updated(1, seqNameWithNamespace)
        val updatedContent = WhiskActionPut(Some(Exec.sequence(updatedSeq.toVector)))

        // update the sequence
        Put(s"$collectionPath/${seqName.name}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.sequenceIsCyclic
        }
    }

    it should "allow creation of sequence provided the number of actions is <= than allowed limit" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname()}_normal")
        // put the component action in the entity store so it's found
        val component = WhiskAction(namespace, aname(), Exec.js("??"))
        put(entityStore, component)
        // create valid exec sequence
        val limit = whiskConfig.actionSequenceLimit.toInt
        val sequence = for (i <- 1 to limit) yield stringToFullyQualifiedName(component.docid())
        val content = WhiskActionPut(Some(Exec.sequence(sequence.toVector)))

        // create an action sequence
        Put(s"$collectionPath/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "allow creation of sequence with actions with package bindings" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname()}_withbindings")

        // create the package
        val pkg = s"${aname()}_pkg"
        val wp = WhiskPackage(namespace, EntityName(pkg), None, publish = true)
        put(entityStore, wp)

        // create binding to wp
        val pkgWithBinding = s"${aname()}_pkgbinding"
        val wpBinding = WhiskPackage(namespace, EntityName(pkgWithBinding), wp.bind)
        put(entityStore, wpBinding)

        // put the action in the entity store so it exists
        val actionName = s"${aname()}_action"
        val namespaceWithPkg = s"/$namespace/$pkg"
        val action = WhiskAction(EntityPath(namespaceWithPkg), EntityName(actionName), Exec.js("??"))
        put(entityStore, action)

        // create sequence that refers to action with binding
        val sequence = Vector(s"/$defaultNamespace/$pkgWithBinding/$actionName").map(stringToFullyQualifiedName(_))
        val content = WhiskActionPut(Some(Exec.sequence(sequence)))

        // create an action sequence
        Put(s"$collectionPath/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[String]
        }
    }

    it should "reject update of sequence with cycle through bindings" in {
        implicit val tid = transid()
        val seqName = EntityName(s"${aname()}_cycle_binding")

        // put the action in the entity store so it's found
        val component = WhiskAction(namespace, aname(), Exec.js("??"))
        put(entityStore, component)
        val sequence = for (i <- 1 to 2) yield stringToFullyQualifiedName(component.docid())

        // create package
        val pkg = s"${aname()}_pkg"
        val wp = WhiskPackage(namespace, EntityName(pkg), None, publish = true)
        put(entityStore, wp)

        // create an action sequence
        val namespaceWithPkg = EntityPath(s"/$namespace/$pkg")
        val content = WhiskActionPut(Some(Exec.sequence(sequence.toVector)))
        Put(s"$collectionPath/$pkg/${seqName.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

        // create binding
        val pkgWithBinding = s"${aname()}_pkgbinding"
        val wpBinding = WhiskPackage(namespace, EntityName(pkgWithBinding), wp.bind)
        put(entityStore, wpBinding)

        // now update the sequence to refer to itself through the binding
        val seqNameWithBinding = stringToFullyQualifiedName(s"/$namespace/$pkgWithBinding/${seqName.name}")
        val updatedSeq = sequence.updated(1, seqNameWithBinding)
        val updatedContent = WhiskActionPut(Some(Exec.sequence(updatedSeq.toVector)))

        // update the sequence
        Put(s"$collectionPath/$pkg/${seqName.name}?overwrite=true", updatedContent) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.sequenceIsCyclic
        }
    }

    it should "reject creation of a sequence with components that don't have at least namespace and action name" in {
        implicit val tid = transid()
        val content = JsObject("exec" -> JsObject("kind" -> Exec.SEQUENCE.toJson, "components" -> Vector("a", "b").toJson))

        Put(s"$collectionPath/${aname()}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            // the content will fail to deserialize on the route directive,
            // and without a custom rejection, the response will be a string
            responseAs[String] shouldBe s"The request content was malformed:\nrequirement failed: ${Messages.malformedFullyQualifiedEntityName}"
        }
    }

    it should "reject update of a sequence with components that don't have at least namespace and action name" in {
        implicit val tid = transid()
        val content = JsObject("exec" -> JsObject("kind" -> Exec.SEQUENCE.toJson, "components" -> Vector("a", "b").toJson))

        // update an action sequence
        Put(s"$collectionPath/${aname()}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            // the content will fail to deserialize on the route directive,
            // and without a custom rejection, the response will be a string
            responseAs[String] shouldBe s"The request content was malformed:\nrequirement failed: ${Messages.malformedFullyQualifiedEntityName}"
        }
    }

    it should "create a sequence of type s -> (x, x) where x is a sequence and correctly count the atomic actions" in {
        implicit val tid = transid()
        val actionCnt = 2

        // make sequence x and install it in db
        val xSeqName = s"${aname()}_x"
        val components = for (i <- 1 to actionCnt) yield s"${aname()}_p"
        putSimpleSequenceInDB(xSeqName, namespace, components.toVector)

        // create an action sequence s
        val sSeqName = s"${aname()}_s"
        val sSeq = makeSimpleSequence(sSeqName, namespace, Vector(xSeqName, xSeqName), false) // x is installed in the db already
        val content = WhiskActionPut(Some(sSeq.exec))

        implicit val stream = new java.io.ByteArrayOutputStream
        this.outputStream = new PrintStream(stream)
        try {
            stream.reset()
            Console.withOut(stream) {
                Put(s"$collectionPath/$sSeqName", content) ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                    logContains(s"atomic action count ${2 * actionCnt}")
                }
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
        val limit = whiskConfig.actionSequenceLimit.toInt
        assert(whiskConfig.actionSequenceLimit.toInt >= 6)
        implicit val tid = transid()
        val actionCnt = 4
        val aAct = s"${aname()}_a"
        val yAct = s"${aname()}_y"
        val yComp = Vector(aAct)
        // make seq y and store it in the db
        putSimpleSequenceInDB(yAct, namespace, yComp)
        val bAct = s"${aname()}_b"
        val zAct = s"${aname()}_z"
        val xAct = s"${aname()}_x"
        val xComp = Vector(bAct, zAct)
        // make sequence x and install it in db
        putSimpleSequenceInDB(xAct, namespace, xComp)
        val sAct = s"${aname()}_s"
        val sSeq = makeSimpleSequence(sAct, namespace, Vector(s"$aAct", s"$xAct", s"$yAct"), false) // a, x, y  in the db already
        // create an action sequence s
        val content = WhiskActionPut(Some(sSeq.exec))

        implicit val stream = new java.io.ByteArrayOutputStream
        this.outputStream = new PrintStream(stream)
        try {
            stream.reset()
            Console.withOut(stream) {
                Put(s"$collectionPath/$sAct", content) ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                }
                logContains("atomic action count 4")
            }

            // update action z to point to s --- should be rejected
            val zUpdate = makeSimpleSequence(zAct, namespace, Vector(s"$sAct"), false) // s in the db already
            val zUpdateContent = WhiskActionPut(Some(zUpdate.exec))
            Put(s"$collectionPath/$zAct?overwrite=true", zUpdateContent) ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                responseAs[ErrorResponse].error shouldBe Messages.sequenceIsCyclic
            }

            // update action s to point to a, s, b --- should be rejected
            val sUpdate = makeSimpleSequence(sAct, namespace, Vector(s"$aAct", s"$sAct", s"$bAct"), false) // s in the db already
            val sUpdateContent = WhiskActionPut(Some(sUpdate.exec))
            Put(s"$collectionPath/$sAct?overwrite=true", sUpdateContent) ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                responseAs[ErrorResponse].error shouldBe Messages.sequenceIsCyclic
            }

            // update action z to point to y
            val zSeq = makeSimpleSequence(zAct, namespace, Vector(s"$yAct"), false) // y  in the db already
            val updateContent = WhiskActionPut(Some(zSeq.exec))
            stream.reset()
            Console.withOut(stream) {
                Put(s"$collectionPath/$zAct?overwrite=true", updateContent) ~> sealRoute(routes(creds)) ~> check {
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
        val components = componentNames map { c => stringToFullyQualifiedName(s"/$ns/$c") }
        // create wsk action for the sequence
        WhiskAction(namespace, EntityName(sequenceName), Exec.sequence(components))
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
