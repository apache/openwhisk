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

package whisk.core.database.test

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }

import akka.actor.ActorSystem

import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.scaladsl._

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import spray.json._
import spray.json.DefaultJsonProtocol._

import org.junit.runner.RunWith

import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._

@RunWith(classOf[JUnitRunner])
class DbPing extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ScalaFutures
    with Matchers {

    override implicit val patienceConfig = PatienceConfig(timeout = 10.seconds, interval = 0.5.seconds)

    implicit val actorSystem      = ActorSystem()
    implicit val executionContext = actorSystem.dispatcher
    implicit val materializer     = ActorMaterializer()

    override def afterAll() {
        println("Shutting down materializer")
        materializer.shutdown()
        println("Shutting down HTTP connections")
        Await.result(akka.http.scaladsl.Http().shutdownAllConnectionPools(), Duration.Inf)
        println("Shutting down actor system")
        actorSystem.terminate()
        Await.result(actorSystem.whenTerminated, Duration.Inf)
    }

    lazy val config = new WhiskConfig(Map(
        dbProvider -> null,
        dbProtocol -> null,
        dbUsername -> null,
        dbPassword -> null,
        dbHost -> null,
        dbPort -> null))

    lazy val dbRootUri = Uri(
        scheme = config.dbProtocol,
        authority = Uri.Authority(
            host = Uri.Host(config.dbHost),
            port = config.dbPort.toInt),
        path = Uri.Path("/")
    )

    lazy val dbRootRequest = HttpRequest(
        headers = List(
            Authorization(BasicHttpCredentials(config.dbUsername, config.dbPassword)),
            Accept(MediaTypes.`application/json`)),
        uri = dbRootUri
    )

    behavior of "Database ping"

    it should "retrieve CouchDB/Cloudant DB info" in {
        assume(config.dbProvider == "Cloudant" || config.dbProvider == "CouchDB")

        val f: Future[JsObject] = Http().singleRequest(dbRootRequest).flatMap { response =>
            Unmarshal(response.entity).to[JsObject]
        }

        whenReady(f) { js =>
            js.fields.get("couchdb") shouldBe defined
            js.fields("couchdb").convertTo[String] should equal("Welcome")
        }
    }
}
