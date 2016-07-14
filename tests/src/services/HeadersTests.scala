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

package services

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.Finders
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.Span.convertDurationToSpan

import akka.actor.ActorSystem
import common.WhiskProperties
import spray.client.pipelining.Get
import spray.client.pipelining.Options
import spray.client.pipelining.Post
import spray.client.pipelining.WithTransformation
import spray.client.pipelining.WithTransformerConcatenation
import spray.client.pipelining.addCredentials
import spray.client.pipelining.sendReceive
import spray.client.pipelining.sendReceive$default$3
import spray.client.pipelining.unmarshal
import spray.http.AllOrigins
import spray.http.BasicHttpCredentials
import spray.http.HttpHeader
import spray.http.HttpHeaders.`Access-Control-Allow-Headers`
import spray.http.HttpHeaders.`Access-Control-Allow-Origin`
import spray.http.HttpMethod
import spray.http.HttpMethods.CONNECT
import spray.http.HttpMethods.DELETE
import spray.http.HttpMethods.GET
import spray.http.HttpMethods.HEAD
import spray.http.HttpMethods.OPTIONS
import spray.http.HttpMethods.PATCH
import spray.http.HttpMethods.POST
import spray.http.HttpMethods.PUT
import spray.http.HttpMethods.TRACE
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.OK
import spray.http.Uri
import spray.http.Uri.Path

import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class HeadersTests extends FlatSpec
    with Matchers
    with ScalaFutures
    with WskActorSystem {

    behavior of "Headers at general API"

    val whiskAuth = WhiskProperties.getBasicAuth
    val creds = BasicHttpCredentials(whiskAuth.fst, whiskAuth.snd)

    val allMethods = Some(Set(DELETE.name, GET.name, POST.name, PUT.name))
    val allowOrigin = `Access-Control-Allow-Origin`(AllOrigins)
    val allowHeaders = `Access-Control-Allow-Headers`("Authorization", "Content-Type")

    val url = Uri(s"http://${WhiskProperties.getControllerHost}:${WhiskProperties.getControllerPort}")
    val pipeline: HttpRequest => Future[HttpResponse] = (
        sendReceive
        ~> unmarshal[HttpResponse])

    implicit val config = PatienceConfig(10 seconds, 0 milliseconds)

    val basePath = Path("/api/v1")

    /**
     * Checks, if the required headers are in the list of all headers.
     * For the allowed method, it checks, if only the allowed methods are in the response headers.
     */
    def containsHeaders(headers: List[HttpHeader], allowedMethods: Option[Set[String]] = None) = {
        headers should contain allOf (allowOrigin, allowHeaders)

        // TODO: commented out for now as allowed methods are not supported currently
        //        val headersMap = headers map { header =>
        //            header.name -> header.value.split(",").map(_.trim).toSet
        //        } toMap
        //        allowedMethods map { allowedMethods =>
        //            headersMap should contain key "Access-Control-Allow-Methods"
        //            headersMap("Access-Control-Allow-Methods") should contain theSameElementsAs (allowedMethods)
        //        }
    }

    it should "respond to OPTIONS with all headers" in {
        pipeline(Options(url.withPath(basePath))).futureValue.headers should contain allOf (allowOrigin, allowHeaders)
    }

    ignore should "not respond to OPTIONS for non existing path" in {
        val path = basePath / "foo" / "bar"

        pipeline(Options(url.withPath(path))).futureValue.status should not be OK
    }

    // Actions
    it should "respond to OPTIONS for listing actions" in {
        val path = basePath / "namespaces" / "barfoo" / "actions"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for actions path" in {
        val path = basePath / "namespaces" / "barfoo" / "actions" / "foobar"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, allMethods)
    }

    it should "respond to POST action with headers" in {
        val path = basePath / "namespaces" / "whisk.system" / "actions" / "samples" / "helloWorld"
        val response = pipeline(Post(url.withPath(path)) ~> addCredentials(creds)) futureValue

        response.status shouldBe Accepted
        containsHeaders(response.headers)
    }

    // Activations
    it should "respond to OPTIONS for listing activations" in {
        val path = basePath / "namespaces" / "barfoo" / "activations"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for activations get" in {
        val path = basePath / "namespaces" / "barfoo" / "activations" / "foobar"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for activations logs" in {
        val path = basePath / "namespaces" / "barfoo" / "activations" / "foobar" / "logs"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for activations results" in {
        val path = basePath / "namespaces" / "barfoo" / "activations" / "foobar" / "result"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to GET for listing activations with Headers" in {
        val path = basePath / "namespaces" / "_" / "activations"
        val response = pipeline(Get(url.withPath(path)) ~> addCredentials(creds)) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers)
    }

    // Namespaces
    it should "respond to OPTIONS for listing namespaces" in {
        val path = basePath / "namespaces"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for namespaces getEntities" in {
        val path = basePath / "namespaces" / "barfoo"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to GET for namespaces getEntities with Headers" in {
        val path = basePath / "namespaces" / "_"
        val response = pipeline(Get(url.withPath(path)) ~> addCredentials(creds)) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers)
    }

    // Packages
    it should "respond to OPTIONS for listing packages" in {
        val path = basePath / "namespaces" / "barfoo" / "packages"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for packages path" in {
        val path = basePath / "namespaces" / "barfoo" / "packages" / "foobar"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("DELETE", "GET", "PUT")))
    }

    it should "respond to GET for listing packages with headers" in {
        val path = basePath / "namespaces" / "_" / "packages"
        val response = pipeline(Get(url.withPath(path)) ~> addCredentials(creds)) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers)
    }

    // Rules
    it should "respond to OPTIONS for listing rules" in {
        val path = basePath / "namespaces" / "barfoo" / "rules"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for rules path" in {
        val path = basePath / "namespaces" / "barfoo" / "rules" / "foobar"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, allMethods)
    }

    it should "respond to GET for listing rules with headers" in {
        val path = basePath / "namespaces" / "_" / "rules"
        val response = pipeline(Get(url.withPath(path)) ~> addCredentials(creds)) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers)
    }

    // Triggers
    it should "respond to OPTIONS for listing triggers" in {
        val path = basePath / "namespaces" / "barfoo" / "triggers"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, Some(Set("GET")))
    }

    it should "respond to OPTIONS for triggers path" in {
        val path = basePath / "namespaces" / "barfoo" / "triggers" / "foobar"
        val response = pipeline(Options(url.withPath(path))) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers, allMethods)
    }

    it should "respond to GET for listing triggers with headers" in {
        val path = basePath / "namespaces" / "_" / "triggers"
        val response = pipeline(Get(url.withPath(path)) ~> addCredentials(creds)) futureValue

        response.status shouldBe OK
        containsHeaders(response.headers)
    }
}
