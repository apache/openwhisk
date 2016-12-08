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

package whisk.core.iam

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http.HttpRequest
import spray.http.Uri
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.entity.Subject

object NamespaceProvider {
    /**
     * An identity provider host (optional).
     * Note: using entitlement host now for compatibility but will need to change to an iam provider in the future
     */
    val optionalProperties = WhiskConfig.iamProviderHost

    /**
     * The default list of namespaces for a subject.
     */
    protected[core] def defaultNamespaces(subject: Subject) = Set(subject())
}

protected[core] class NamespaceProvider(config: WhiskConfig, timeout: FiniteDuration = 5 seconds, forceLocal: Boolean = false)(
    implicit actorSystem: ActorSystem)
    extends Logging {

    private implicit val executionContext = actorSystem.dispatcher
    private val apiLocation = config.iamProviderHost
    // an iam provider service will have a valid host:port definition and will not be equals ":" or ":xxxx"
    private val useProvider = !forceLocal && !apiLocation.startsWith(":")

    /**
     * Gets the set of namespaces the subject has rights to (may be empty).
     * The default set of namespaces contains only one entry, which is the subject name.
     *
     * @param subject the subject to lookup namespaces for
     * @return a promise that completes with list of namespaces the subject has rights to
     */
    protected[core] def namespaces(subject: Subject)(implicit transid: TransactionId): Future[Set[String]] = {
        if (useProvider) {
            info(this, s"getting namespaces from ${apiLocation}")

            val url = Uri("http://" + apiLocation + "/namespaces").withQuery(
                "subject" -> subject())

            val pipeline: HttpRequest => Future[Set[String]] = (
                addHeader("X-Transaction-Id", transid.toString())
                ~> sendReceive
                ~> unmarshal[Set[String]])

            pipeline(Get(url))
        } else {
            info(this, s"assuming default namespaces")
            Future.successful(NamespaceProvider.defaultNamespaces(subject))
        }
    }
}
