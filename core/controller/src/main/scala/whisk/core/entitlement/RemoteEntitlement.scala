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
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import spray.client.pipelining.Get
import spray.client.pipelining.Post
import spray.client.pipelining.WithTransformerConcatenation
import spray.client.pipelining.addHeader
import spray.client.pipelining.sendReceive
import spray.client.pipelining.unmarshal
import spray.http.FormData
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes.OK
import spray.http.Uri
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.entity.Subject

protected[core] class RemoteEntitlementService(
    private val config: WhiskConfig,
    private val timeout: FiniteDuration = 5 seconds)(
    private implicit val actorSystem: ActorSystem)
    extends EntitlementService(config) {

    private implicit val executionContext = actorSystem.dispatcher

    private val apiLocation = config.entitlementHost
    private val matrix = TrieMap[(Subject, String), Set[Privilege]]()

    protected[core] override def namespaces(subject: Subject)(implicit transid: TransactionId): Future[Set[String]] = {
        info(this, s"getting namespaces from ${apiLocation}")

        val url = Uri("http://" + apiLocation + "/namespaces").withQuery(
            "subject" -> subject())

        val pipeline: HttpRequest => Future[Set[String]] = (
            addHeader("X-Transaction-Id", transid.toString())
            ~> sendReceive
            ~> unmarshal[Set[String]])
        pipeline(Get(url))
    }

    protected[core] override def grant(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean] = {
        val url = Uri("http://" + apiLocation + "/grant")

        val form = FormData(Seq(
            "subject" -> subject(),
            "right" -> right.toString,
            "resource" -> resource.entity.getOrElse(""),
            "collection" -> resource.collection.toString,
            "namespace" -> resource.namespace.toString))

        val promise = Promise[Boolean]
        val pipeline: HttpRequest => Future[HttpResponse] = (
            addHeader("X-Transaction-Id", transid.toString())
            ~> sendReceive)
        pipeline(Post(url, form)) onComplete {
            case Success(response) =>
                response.status match {
                    case OK => promise.success(true)
                    case _  => promise.success(false)
                }
            case Failure(t) =>
                error(this, s"failed while granting rights: ${t.getMessage}")
                promise.success(false)
        }
        promise.future
    }

    protected[core] override def revoke(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean] = {
        val url = Uri("http://" + apiLocation + "/revoke")

        val form = FormData(Seq(
            "subject" -> subject(),
            "right" -> right.toString,
            "resource" -> resource.entity.getOrElse(""),
            "collection" -> resource.collection.toString,
            "namespace" -> resource.namespace.toString))

        val promise = Promise[Boolean]
        val pipeline: HttpRequest => Future[HttpResponse] = (
            addHeader("X-Transaction-Id", transid.toString())
            ~> sendReceive)
        pipeline(Post(url, form)) onComplete {
            case Success(response) =>
                response.status match {
                    case OK => promise.success(true)
                    case _  => promise.success(false)
                }
            case Failure(t) =>
                error(this, s"failed while revoking rights: ${t.getMessage}")
                promise.success(false)
        }
        promise.future
    }

    protected override def entitled(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean] = {
        info(this, s"checking namespace at ${apiLocation}")

        val url = Uri("http://" + apiLocation + "/check").withQuery(
            "subject" -> subject(),
            "right" -> right.toString,
            "resource" -> resource.entity.getOrElse(""),
            "collection" -> resource.collection.toString,
            "namespace" -> resource.namespace.toString)

        val promise = Promise[Boolean]
        val pipeline: HttpRequest => Future[HttpResponse] = (
            addHeader("X-Transaction-Id", transid.toString())
            ~> sendReceive)
        pipeline(Get(url)) onComplete {
            case Success(response) =>
                response.status match {
                    case OK => promise.success(true)
                    case _  => promise.success(false)
                }
            case Failure(t) =>
                error(this, s"failed while checking entitlement: ${t.getMessage}")
                promise.success(false)
        }
        promise.future
    }
}
