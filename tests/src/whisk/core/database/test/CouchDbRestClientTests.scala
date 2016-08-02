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
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.DurationDouble
import scala.util._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.http.scaladsl.model._

import spray.json._
import spray.json.DefaultJsonProtocol._

import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.database.CouchDbRestClient

import whisk.test.http.RESTProxy

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class CouchDbRestClientTests extends FlatSpec
    with Matchers
    with ScalaFutures
    with WskActorSystem {

    override implicit val patienceConfig = PatienceConfig(timeout = 10.seconds, interval = 0.5.seconds)

    val config = new WhiskConfig(Map(
        dbProvider -> null,
        dbProtocol -> null,
        dbUsername -> null,
        dbPassword -> null,
        dbHost -> null,
        dbPort -> null))

    val dbAuthority = Uri.Authority(
        host = Uri.Host(config.dbHost),
        port = config.dbPort.toInt
    )

    val proxyPort = 15975
    val proxyActor = actorSystem.actorOf(Props(new RESTProxy("0.0.0.0", proxyPort)(dbAuthority, config.dbProtocol == "https")))

    // the (fake) DB name doesn't matter as this test only exercises the "/" endpoint ("instance info")
    val client = CouchDbRestClient.make("http", "localhost", proxyPort, config.dbUsername, config.dbPassword, "fakeDB")

    def checkResponse(response: Either[StatusCode,JsObject]) : Unit = response match {
        case Right(obj) =>
            assert(obj.fields.contains("couchdb"), "response object doesn't contain 'couchdb'")

        case Left(code) =>
            assert(false, s"unsuccessful response (code ${code.intValue})")
    }

    behavior of "CouchDbRestClient"

    ignore /*it*/ should "successfully access the DB instance info" in {
        assume(config.dbProvider == "Cloudant" || config.dbProvider == "CouchDB")
        val f = client.instanceInfo()
        whenReady(f) { e => checkResponse(e) }
    }

    ignore /*it*/ should "successfully access the DB despite transient connection failures" in {
        // sprays the client with requests, makes sure they are all answered
        // despite temporary connection failure.
        val numRequests = 30
        val timeSpan = 5.seconds
        val delta = timeSpan / numRequests

        val promises = Vector.fill(numRequests)(Promise[Either[StatusCode,JsObject]])

        for(i <- 0 until numRequests) {
            actorSystem.scheduler.scheduleOnce(delta * (i+1)) {
                client.instanceInfo().andThen({ case r => promises(i).tryComplete(r) })
            }
        }

        // Mayhem! Havoc!
        actorSystem.scheduler.scheduleOnce(2.5.seconds, proxyActor, RESTProxy.UnbindFor(1.second))

        // What a type!
        val futures: Vector[Future[Try[Either[StatusCode,JsObject]]]] =
            promises.map(_.future.map(e => Success(e)).recover { case t: Throwable => Failure(t) })

        whenReady(Future.sequence(futures), Timeout(timeSpan * 2)) { results =>
            // We check that the first result was OK
            // (i.e. the service worked before the disruption)
            results.head.toOption shouldBe defined
            checkResponse(results.head.get)

            // We check that the last result was OK
            // (i.e. the service worked again after the disruption)
            results.last.toOption shouldBe defined
            checkResponse(results.last.get)

            // We check that there was at least one error
            // (i.e. we did manage to unbind for a while)
            results.find(_.isFailure) shouldBe defined
        }
    }
}
