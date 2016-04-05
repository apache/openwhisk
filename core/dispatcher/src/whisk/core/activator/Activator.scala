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

package whisk.core.activator

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.matching.Regex.Match

import akka.actor.ActorSystem
import spray.http.HttpRequest
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.connector.LoadBalancerResponse
import whisk.core.connector.LoadbalancerRequest
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.dispatcher.DispatchRule
import whisk.core.dispatcher.Dispatcher
import whisk.core.dispatcher.Registrar
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.Status
import whisk.core.entity.Subject
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskRule
import whisk.http.BasicRasService

/**
 * A kafka message handler that interprets a database of whisk rules, and
 * maps triggers to action invocations. The activator subscribes to topics
 * "[enable,disable]/(.+)" where (.+) is the rule id.
 *
 * Activating or deactivating a rule are mutually exclusive operations: either
 * a rule status is changing form active -> inactive or inactive -> active. It
 * is possible that multiple activations for the same rule are in progress and
 * at most one of these may succeed (in changing the status from inactive to active).
 * At most one since there is no guarantee that writing the status update to the
 * datastore will succeed. The same holds for deactivations: multiple deactivations
 * of the same rule may be in progress and there is no guarantee that any will succeed,
 * but at most one may.
 *
 * While a rule status change is in progress, there is no guarantee that triggers
 * received on the kafka bus will match the rule since atomicity of the datastore
 * write and the updating the dispatcher subscribers cannot be guaranteed at the
 * necessary granularity.
 */
class Activator(
    config: WhiskConfig,
    registrar: Registrar,
    implicit private val actorSystem: ActorSystem,
    implicit private val executionContext: ExecutionContext)
    extends DispatchRule("activator", "/rules", s"""(${Status.ACTIVATING})/(.+),(${Status.DEACTIVATING})/(.+)""")
    with LoadbalancerRequest
    with Logging {

    /**
     * Enables or disables a rule.
     *
     * @param msg is a JSON object { subject: "the subject id enabling/disabling rule" }
     * @param matches dictates if request is to enable or disable a rule and the rule id
     * @return Future[DocInfo] with document id and revision if the status change was successful
     */
    override def doit(msg: Message, matches: Seq[Match])(implicit transid: TransactionId): Future[DocInfo] = {
        Future {
            // conformance checks can terminate the future if a variance is detected
            require(matches != null && matches.size == 1, "matches undefined")
            require(matches(0).group(2).nonEmpty, "enable/disable toggle undefined")
            require(matches(0).group(2) == Status.ACTIVATING.toString || matches(0).group(2) == Status.DEACTIVATING.toString, "toggle not recognized")
            require(matches(0).group(3).nonEmpty, "rule undefined")
            require(msg != null, "message undefined")

            val regex = matches(0)
            val enable = regex.group(2) == Status.ACTIVATING.toString
            val rule = DocId(regex.group(3))
            val subject = msg.subject
            info(this, s"'$subject' requests rule '${rule.id}' enabled = $enable")
            (enable, rule, subject)
        } flatMap {
            case (enable, rule, subject) =>
                // bypass cache and fetch from datastore directly since controller may have updated record
                val fetch = WhiskRule.get(datastore, rule.asDocInfo, false) map { r => (enable, r, subject) }
                fetch onFailure { case t => error(this, s"failed to fetch rule '${rule.id}': ${t.getMessage}") }
                fetch
        } flatMap {
            // changing status (active -> inactive) or (inactive -> active) is mutually exclusive
            case (true, rule, subject) =>
                // create a trigger -> action listener even if trigger or action is no longer valid (e.g., was deleted);
                // defer cleanup to a nanny that collects trigger -> action listeners when they are no longer valid
                require(rule.status == Status.ACTIVATING, s"expected rule '${rule.fullyQualifiedName}' to be activating but status is ${rule.status}")
                enableRule(rule, subject)
            case (false, rule, subject) =>
                require(rule.status == Status.DEACTIVATING, s"expected rule '${rule.fullyQualifiedName}' to be deactivating but status is ${rule.status}")
                disableRule(rule, subject)
        }
    }

    /**
     * Enables a rule.
     *
     * @param rule the rule to enable
     * @param subject the subject that is enabling this rule
     * @return Future[DocInfo] with document id and revision if the status change was successful
     */
    private def enableRule(rule: WhiskRule, subject: Subject)(implicit transid: TransactionId): Future[DocInfo] = {
        // Creates the rule handler to post invokes when a trigger matches the rule. No handler should
        // already exist for this rule. Enabling a rule is only permitted when the rule status in the
        // datastore was INACTIVE. At most one enable transaction per rule is permitted at any given time.
        val post = makePost(rule, subject)
        registrar.addHandler(post, replace = false) map { prev =>
            val msg = s"nothing to do, found a previous handler for rule '${rule.fullyQualifiedName}' although none expected"
            info(this, msg)
            Future.failed { new IllegalStateException(msg) }
        } getOrElse {
            // If the invariant above holds, write ACTIVE status back to the datastore to unlock the rule status. If
            // the invariant does not hold, then leave the rule status in the datastore as is and abort. This suggests
            // another request to activate the rule was completed.
            val unlock = WhiskRule.put(datastore, rule.toggle(Status.ACTIVE))
            unlock onComplete {
                case Success(_) =>
                    info(this, s"rule '${rule.fullyQualifiedName}' active in datastore, handler '{$post.name}' active")
                case Failure(t) =>
                    // Failed to write status back to the datastore so undo the handler registratipn and leave the
                    // record status unchanged to permit state stabilization elsewhere. The invariant to re-establish
                    // here is that no handler should exist for the rule.
                    val removed = registrar.removeHandler(post)
                    error(this, s"failed to set rule '${rule.fullyQualifiedName}' active, handler removed = ${removed.isDefined}: ${t.getMessage}")
            }
            unlock
        }
    }

    /**
     * Disables a rule.
     *
     * @param rule the rule to disable
     * @param subject the subject that is disabling this rule
     * @return Future[DocInfo] with document id and revision if the status change was successful
     */
    private def disableRule(rule: WhiskRule, subject: Subject)(implicit transid: TransactionId): Future[DocInfo] = {
        // Finds handler for this rule. Exactly one handler should exist since disabling a rule is only
        // permitted when the status of the rule in the datastore was ACTIVE. Remove the handler so that
        // no new triggers can match this rule. Further, at most one disable transaction per rule is
        // permitted at any given time.
        registrar.removeHandler(getUniqueName(rule.fullyQualifiedName, subject)) map { post =>
            // If the invariant above holds, write INACTIVE status back to the datastore to unlock the
            // rule status. If the invariant does not hold, then leave the rule status in the datastore
            // in its current state and abort.
            val unlock = WhiskRule.put(datastore, rule.toggle(Status.INACTIVE))
            unlock onComplete {
                case Success(_) =>
                    info(this, s"rule '${rule.fullyQualifiedName}' inactive in datastore, handler '${post.name}' inactive")
                case Failure(t) =>
                    // Failed to write status back to the datastore, so confirm the invariant here that
                    // no handler exists for the rule rule and let the state of the rule in the datastore recover elsewhere.
                    val removed = registrar.removeHandler(getUniqueName(rule.fullyQualifiedName, subject))
                    error(this, s"failed to set rule '${rule.fullyQualifiedName}' inactive, handler exists = ${removed.isDefined}: ${t.getMessage}")
            }
            unlock
        } getOrElse {
            val msg = s"nothing to do, expected a previous handler for rule '${rule.fullyQualifiedName}' but none found"
            info(this, msg)
            Future.failed { new IllegalStateException(msg) }
        }
    }

    private def makePost(rule: WhiskRule, subject: Subject): PostInvoke = {
        val instance = s"${getUniqueName(rule.fullyQualifiedName, subject)}"
        val triggerId = WhiskEntity.qualifiedName(rule.namespace, rule.trigger)
        val actionId = WhiskEntity.qualifiedName(rule.namespace, rule.action)
        val post = new PostInvoke(instance, rule, subject, config) with (HttpRequest => Future[LoadBalancerResponse]) {
            override def apply(r: HttpRequest): Future[LoadBalancerResponse] = {
                postLoadBalancerRequest(r)
            }
        }
        post.setVerbosity(Verbosity.Loud)
        post
    }

    private def getUniqueName(name: String, subject: Subject): String = s"$subject.$name"
    private val postLoadBalancerRequest = request(config.loadbalancerHost)
    private val datastore = WhiskEntityStore.datastore(config)
    datastore.setVerbosity(Verbosity.Loud)
}

object Activator {
    /**
     * An object which records the environment variables required for this component to run.
     */
    def requiredProperties = WhiskEntityStore.requiredProperties
}

object ActivatorService {
    def requiredProperties =
        Activator.requiredProperties ++
            PostInvoke.requiredProperties ++
            Dispatcher.requiredProperties ++
            WhiskConfig.consulServer

    val actorSystem = ActorSystem("activator")

    def main(args: Array[String]): Unit = {
        val config = new WhiskConfig(requiredProperties)

        if (config.isValid) {
            val dispatcher = new Dispatcher(config, "whisk", "activator")
            val activator = new Activator(config, dispatcher, actorSystem, Dispatcher.executionContext)

            activator.setVerbosity(Verbosity.Loud)
            dispatcher.setVerbosity(Verbosity.Loud)
            dispatcher.addHandler(activator, true)
            dispatcher.start()

            BasicRasService.startService("activator", "0.0.0.0", config.servicePort.toInt)
        }
    }
}
