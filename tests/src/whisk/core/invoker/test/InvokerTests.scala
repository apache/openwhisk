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

package whisk.core.invoker.test

import java.util.Calendar

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json.JsObject
import spray.json.JsString
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.logsDir
import whisk.core.WhiskConfig.selfDockerEndpoint
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.container.ContainerUtils
import whisk.core.database.test.DbUtils
import whisk.core.entity.ActivationId
import whisk.core.entity.AuthKey
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.Namespace
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntityStore
import whisk.core.invoker.Invoker
import scala.language.postfixOps

@RunWith(classOf[JUnitRunner])
class InvokerTests extends FlatSpec
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterAll
    with DbUtils {

    implicit val actorSystem = ActorSystem()

    val config = new WhiskConfig(
        Invoker.requiredProperties ++ Map(
            selfDockerEndpoint -> "localhost",
            logsDir -> System.getProperty("java.io.tmpdir")))
    assert(config.isValid)
    val datastore = WhiskEntityStore.datastore(config)
    val authstore = WhiskAuthStore.datastore(config)
    val activationstore = WhiskActivationStore.datastore(config)

    after {
        cleanup()
    }

    override def afterAll() {
        println("Shutting down store connections")
        datastore.shutdown()
        authstore.shutdown()
        activationstore.shutdown()
        println("Shutting down HTTP connections")
        Await.result(akka.http.scaladsl.Http().shutdownAllConnectionPools(), Duration.Inf)
        println("Shutting down actor system")
        actorSystem.terminate()
        Await.result(actorSystem.whenTerminated, Duration.Inf)
    }

    behavior of "Invoker"

    def invokeAction(wsk: WhiskAction, auth: WhiskAuth, args: String) = {
        implicit val tid = transid()
        val payload = JsObject("payload" -> JsString(args))
        val message = Message(transid, "", auth.subject, ActivationId(), Some(payload), None)
        implicit val stream = new java.io.ByteArrayOutputStream
        val activationDoc = Console.withOut(stream) {
            val rule = new Invoker(config, 1, Verbosity.Loud, false)
            val future = rule.doit("someTopic", message, rule.matches(s"/actions/invoke/${wsk.docid}"))
            val docinfo = Await.result(future, 10 seconds)
            val activationResult = Await.result(WhiskActivation.get(activationstore, docinfo), dbOpTimeout)
            Await.result(WhiskActivation.del(activationstore, docinfo), dbOpTimeout)
            activationResult
        }

        val result = stream.toString
        //println(result)

        // We want the most permissive regex so that changes in naming convention won't break this.
        val container = "(wsk\\w*[0-9]+Z)".r.findFirstIn(result)
        val dockerutil = new ContainerUtils { val dockerhost = config.selfDockerEndpoint }
        val logs = dockerutil.getContainerLogs(container).getOrElse("none")
        //println(logs)

        dockerutil.killContainer(container)
        dockerutil.rmContainer(container)

        activationDoc.activationId should be(message.activationId)
        result should include regex ("sending initialization to container")
        result should include regex (s"sending arguments to ${wsk.fullyQualifiedName}")
        (activationDoc.logs, logs)
    }

    ignore should "invoke Javascript action" in {
        implicit val tid = transid()
        val auth = WhiskAuth(Subject(), AuthKey())
        val wsk = WhiskAction(
            Namespace("somenamespace"),
            EntityName("invokerHelloTest"),
            Exec.js("""function main(msg) { console.log('Hi '+msg.payload);}"""))

        put(authstore, auth)
        put(datastore, wsk)
        val today = Calendar.getInstance().getTime().toString
        val logs = invokeAction(wsk, auth, today)
        logs._1.toString should include regex (s"Hi $today") // issue 932, using logs._2 occasionally fails
    }
}
