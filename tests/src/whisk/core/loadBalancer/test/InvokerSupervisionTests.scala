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

package whisk.core.loadBalancer.test

import akka.testkit.TestFSMRef
import scala.concurrent.duration._
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import whisk.core.loadBalancer.InvokerActor
import akka.pattern.ask
import whisk.core.loadBalancer.Offline
import whisk.core.loadBalancer.Healthy
import whisk.core.loadBalancer.GetStatus
import akka.util.Timeout
import scala.concurrent.Await
import whisk.core.loadBalancer.InvokerInfo
import akka.testkit.TestActorRef
import whisk.core.loadBalancer.InvokerPool
import akka.actor.FSM
import akka.actor.ActorRef
import common.WskActorSystem
import whisk.core.connector.PingMessage

@RunWith(classOf[JUnitRunner])
class InvokerSupervisionTests extends FlatSpec with Matchers with WskActorSystem {
    implicit val timeout = Timeout(5.seconds)

    /** Imitates a StateTimeout in the FSM */
    def timeout(actor: ActorRef) = actor ! FSM.StateTimeout

    /** Queries all invokers for their state */
    def allStates(pool: ActorRef) = Await.result(pool.ask(GetStatus).mapTo[Map[String, InvokerInfo]], 5.seconds)

    behavior of "InvokerPool"

    it should "successfully create invokers in its pool on ping" in {
        val supervisor = TestActorRef(new InvokerPool(_ => ()))

        // Create one invoker
        supervisor ! PingMessage("invoker0")
        supervisor.children should have size 1

        // Get the status of that invoker
        allStates(supervisor) shouldBe Map("invoker0" -> Healthy)

        // Create another invoker
        supervisor ! PingMessage("invoker1")
        supervisor.children should have size 2

        // Shouldn't create another invoker if ping with the same name is sent
        supervisor ! PingMessage("invoker0")
        supervisor.children should have size 2
    }

    it should "forward a ping to the appropriate invoker, calling the provided callback accordingly" in {
        var callbackCalled = 0
        val supervisor = TestActorRef(new InvokerPool(_ => callbackCalled = callbackCalled + 1))

        // Create two invokers
        supervisor ! PingMessage("invoker0")
        supervisor ! PingMessage("invoker1")
        supervisor.children should have size 2

        // Check that both invokers are healthy
        allStates(supervisor) shouldBe Map("invoker0" -> Healthy, "invoker1" -> Healthy)

        // Switch off one of the invokers
        timeout(supervisor.getSingleChild("invoker0"))
        allStates(supervisor) shouldBe Map("invoker0" -> Offline, "invoker1" -> Healthy)

        // Ping that invoker to bring it back up
        supervisor ! PingMessage("invoker0")
        allStates(supervisor) shouldBe Map("invoker0" -> Healthy, "invoker1" -> Healthy)
        callbackCalled shouldBe 1
    }

    behavior of "InvokerActor"

    it should "start healthy, go offline if the state times out and goes healthy on a successful ping again" in {
        val invoker = TestFSMRef(new InvokerActor)
        invoker.stateName shouldBe Healthy

        // Turn the invoker offline
        timeout(invoker)
        invoker.stateName shouldBe Offline

        // Ping it to bring it back up
        invoker ! PingMessage("testinvoker")
        invoker.stateName shouldBe Healthy
    }
}
