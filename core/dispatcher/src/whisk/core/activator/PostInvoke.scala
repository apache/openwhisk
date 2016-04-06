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

import java.time.Clock
import java.time.Instant
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.matching.Regex.Match
import akka.actor.ActorSystem
import spray.client.pipelining.Post
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.mapFormat
import spray.json.DefaultJsonProtocol.JsValueFormat
import spray.json.RootJsonFormat
import spray.json.JsString
import spray.json.JsObject
import spray.json.pimpString
import spray.json.pimpAny
import whisk.common.HttpUtils
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{controllerHost, loadbalancerHost}
import whisk.core.dispatcher.DispatchRule
import whisk.core.dispatcher.Dispatcher
import whisk.core.entity.ActivationId
import whisk.core.entity.Subject
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskRule
import scala.util.Try
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.ActivationMessage.{ publish, INVOKER }
import whisk.core.connector.LoadBalancerResponse
import whisk.core.connector.LoadbalancerRequest
import whisk.core.entity.DocInfo
import spray.http.HttpRequest

class PostInvoke(
    override val name: String,
    rule: WhiskRule,
    subject: Subject,
    config: WhiskConfig)(
        implicit val actorSystem: ActorSystem)
    extends DispatchRule(name, "/triggers/fire", WhiskEntity.qualifiedName(rule.namespace, rule.trigger)) {

    /** Inject method to post HTTP request to loadbalancer and return LoadBalancerResponse. */
    loadbalancerRequest: (HttpRequest => Future[LoadBalancerResponse]) =>

    /**
     * Posts invoke request as a kafka message.
     */
    override def doit(msg: Message, matches: Seq[Match])(implicit transid: TransactionId): Future[DocInfo] = {
        val ruleActivationId = ActivationId()
        val message = Message(transid, s"/actions/invoke/$actionId", subject, ActivationId(), msg.content, Some(ruleActivationId))
        info("PostInvoke", s"[POST] rule activation id: $ruleActivationId")
        info("PostInvoke", s"[POST] action activation id: ${message.activationId}")
        val start = Instant.now(Clock.systemUTC())
        val postInvoke = loadbalancerRequest(Post(publish(INVOKER), message.toJson.asJsObject)) flatMap {
            response =>
                val end = Instant.now(Clock.systemUTC())
                val activation = WhiskActivation(
                    namespace = subject.namespace, // all activations should end up in the one space regardless rule.namespace,
                    name = rule.name,
                    subject,
                    ruleActivationId,
                    start = start,
                    end = end,
                    cause = Some(msg.activationId),
                    version = rule.version)
                info(this, s"posted invoke for '$actionId' status: ${response}")
                WhiskActivation.put(activationStore, activation)
        }

        postInvoke onComplete {
            case Success(_) => info(this, s"activation record for '$describe' recorded in datastore with id '$ruleActivationId'")
            case Failure(t) => error(this, s"post invoke '$describe' failed: ${t.getMessage}")
        }

        postInvoke
    }

    override def toString() = describe

    private val actionId = WhiskEntity.qualifiedName(rule.namespace, rule.action)
    private val triggerId = WhiskEntity.qualifiedName(rule.namespace, rule.trigger)

    private val activationStore = WhiskActivationStore.datastore(config)
    private implicit val ec = Dispatcher.executionContext
    private def describe = s"${triggerId} => ${actionId} ($name)"
}

object PostInvoke {
    def requiredProperties = loadbalancerHost ++ controllerHost
}
