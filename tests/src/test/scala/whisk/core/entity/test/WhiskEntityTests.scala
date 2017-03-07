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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import whisk.core.entity.EntityPath
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.WhiskAction
import whisk.core.entity.DocRevision
import whisk.core.entity.Parameters
import whisk.core.entity.FullyQualifiedEntityName
import whisk.core.entity.SequenceExec
import whisk.core.entity.WhiskPackage
import whisk.core.entity.WhiskActivation
import whisk.core.entity.Subject
import whisk.core.entity.ActivationId
import java.time.Instant
import whisk.core.entity.ActivationLogs
import whisk.core.entity.WhiskTrigger
import whisk.core.entity.ReducedRule
import whisk.core.entity.Status

@RunWith(classOf[JUnitRunner])
class WhiskEntityTests extends FlatSpec with Matchers {

    val namespace = EntityPath("testspace")
    val name = EntityName("testname")
    val revision = DocRevision("test")

    behavior of "WhiskAction"

    it should "correctly inherit parameters and preserve revision through the process" in {
        def withParameters(p: Parameters) = WhiskAction(
            namespace,
            name,
            Exec.js("js1"), parameters = p).revision[WhiskAction](revision)

        val toInherit = Parameters("testParam", "testValue")
        Seq(Parameters(),
            Parameters("testParam2", "testValue"),
            Parameters("testParam", "testValue2")
        ).foreach { params =>
                val action = withParameters(params)
                val inherited = action.inherit(toInherit)
                inherited shouldBe action.copy(parameters = toInherit ++ action.parameters)
                inherited.rev shouldBe action.rev
            }
    }

    it should "correctly resolve default namespace and preserve its revision through the process" in {
        val user = "testuser"
        val sequenceAction = FullyQualifiedEntityName(EntityPath("_"), EntityName("testaction"))
        val action = WhiskAction(
            namespace,
            name,
            Exec.sequence(Vector(sequenceAction))).revision[WhiskAction](revision)

        val resolved = action.resolve(EntityName(user))
        resolved.exec.asInstanceOf[SequenceExec].components.head shouldBe sequenceAction.copy(path = EntityPath(user))
        action.rev shouldBe resolved.rev
    }

    behavior of "WhiskPackage"

    it should "correctly inherit parameters and preserve revision through the process" in {
        def withParameters(p: Parameters) = WhiskPackage(
            namespace,
            name, parameters = p).revision[WhiskPackage](revision)

        val toInherit = Parameters("testParam", "testValue")
        Seq(Parameters(),
            Parameters("testParam2", "testValue"),
            Parameters("testParam", "testValue2")
        ).foreach { params =>
                val pkg = withParameters(params)
                val inherited = pkg.inherit(toInherit)
                inherited shouldBe pkg.copy(parameters = toInherit ++ pkg.parameters)
                inherited.rev shouldBe pkg.rev
            }
    }

    it should "correctly merge parameters and preserve revision through the process" in {
        def withParameters(p: Parameters) = WhiskPackage(
            namespace,
            name, parameters = p).revision[WhiskPackage](revision)

        val toOverride = Parameters("testParam", "testValue")
        Seq(Parameters(),
            Parameters("testParam2", "testValue"),
            Parameters("testParam", "testValue2")
        ).foreach { params =>
                val pkg = withParameters(params)
                val inherited = pkg.mergeParameters(toOverride)
                inherited shouldBe pkg.copy(parameters = pkg.parameters ++ toOverride)
                inherited.rev shouldBe pkg.rev
            }
    }

    behavior of "WhiskActivation"

    it should "add and remove logs and preserve revision in the process" in {
        val activation = WhiskActivation(
            namespace,
            name,
            Subject(),
            ActivationId(),
            Instant.now(),
            Instant.now()).revision[WhiskActivation](revision)
        val logs = ActivationLogs(Vector("testlog"))

        val withLogs = activation.withLogs(logs)
        withLogs shouldBe activation.copy(logs = logs)
        withLogs.rev shouldBe activation.rev

        val withoutLogs = withLogs.withoutLogs
        withoutLogs shouldBe activation
        withoutLogs.rev shouldBe activation.rev
    }

    behavior of "WhiskTrigger"

    it should "add and remove rules and preserve revision in the process" in {
        val fqn = FullyQualifiedEntityName(namespace, name)
        val trigger = WhiskTrigger(
            namespace,
            name).revision[WhiskTrigger](revision)
        val rule = ReducedRule(fqn, Status.ACTIVE)

        // Add a rule
        val ruleAdded = trigger.addRule(fqn, rule)
        ruleAdded.rules shouldBe Some(Map(fqn -> rule))
        ruleAdded.rev shouldBe trigger.rev

        // Remove the rule
        val ruleRemoved = ruleAdded.removeRule(fqn)
        ruleRemoved.rules shouldBe Some(Map.empty[FullyQualifiedEntityName, ReducedRule])
        ruleRemoved.rev shouldBe trigger.rev

        // Remove all rules
        val rulesRemoved = ruleAdded.withoutRules
        rulesRemoved.rules shouldBe None
        rulesRemoved.rev shouldBe trigger.rev
    }
}
