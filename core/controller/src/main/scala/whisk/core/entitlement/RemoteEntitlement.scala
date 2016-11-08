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

package whisk.core.entitlement

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import Privilege.Privilege
import akka.actor.ActorSystem
import spray.client.pipelining._
import spray.http.FormData
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.Unauthorized
import spray.http.Uri
import spray.httpx.UnsuccessfulResponseException
import spray.json.DefaultJsonProtocol
import spray.json.pimpString
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.controller.RejectRequest
import whisk.core.entity.Subject
import whisk.core.loadBalancer.LoadBalancer
import whisk.core.iam.NamespaceProvider

protected[core] class RemoteEntitlementService(
    private val config: WhiskConfig,
    private val loadBalancer: LoadBalancer,
    private val iam: NamespaceProvider,
    private val timeout: FiniteDuration = 5 seconds)(
        private implicit val actorSystem: ActorSystem)
    extends EntitlementService(config, loadBalancer, iam) {

    private implicit val executionContext = actorSystem.dispatcher

    private val apiLocation = config.entitlementHost
    private val matrix = TrieMap[(Subject, String), Set[Privilege]]()

    protected[core] override def grant(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean] = {
        val url = Uri("http://" + apiLocation + "/grant")

        val form = FormData(Seq(
            "subject" -> subject(),
            "right" -> right.toString,
            "resource" -> resource.entity.getOrElse(""),
            "collection" -> resource.collection.toString,
            "namespace" -> resource.namespace.toString))

        val pipeline: HttpRequest => Future[HttpResponse] = (
            addHeader("X-Transaction-Id", transid.toString())
            ~> sendReceive)

        request(pipeline(Post(url, form))) map { _.status == OK } recover { case _ => false }
    }

    protected[core] override def revoke(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean] = {
        val url = Uri("http://" + apiLocation + "/revoke")

        val form = FormData(Seq(
            "subject" -> subject(),
            "right" -> right.toString,
            "resource" -> resource.entity.getOrElse(""),
            "collection" -> resource.collection.toString,
            "namespace" -> resource.namespace.toString))

        val pipeline: HttpRequest => Future[HttpResponse] = (
            addHeader("X-Transaction-Id", transid.toString())
            ~> sendReceive)

        request(pipeline(Post(url, form))) map { _.status == OK } recover { case _ => false }
    }

    protected override def entitled(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean] = {
        info(this, s"checking namespace at ${apiLocation}")

        val url = Uri("http://" + apiLocation + "/check").withQuery(
            "subject" -> subject(),
            "right" -> right.toString,
            "resource" -> resource.entity.getOrElse(""),
            "collection" -> resource.collection.toString,
            "namespace" -> resource.namespace.toString)

        val pipeline: HttpRequest => Future[HttpResponse] = (
            addHeader("X-Transaction-Id", transid.toString())
            ~> sendReceive)

        request(pipeline(Get(url))) map { _.status == OK } recover { case _ => false }
    }

    /** Error response as sent by the remote entitlement service */
    private case class ErrorResponse(error: String)
    private object ErrorResponse extends DefaultJsonProtocol {
        implicit val serdes = jsonFormat1(ErrorResponse.apply)
    }

    private def request[A](initial: Future[A])(implicit transid: TransactionId): Future[A] =
        initial recover {
            case usr: UnsuccessfulResponseException if usr.response.status == Unauthorized =>
                info(this, s"authentication deemed invalid by the entitlement service, response: ${usr.getMessage}")
                val error = Try {
                    usr.response.entity.asString.parseJson.convertTo[ErrorResponse].error
                }.toOption

                val message = Seq("Authentication invalid at entitlement service") ++ error
                throw RejectRequest(Unauthorized, message.mkString(": "))
        }
}
