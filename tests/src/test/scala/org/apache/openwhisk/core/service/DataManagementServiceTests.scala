/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.service

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActor, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import common.StreamLogging
import org.apache.openwhisk.core.entity.SchedulerInstanceId
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class DataManagementServiceTests
    extends TestKit(ActorSystem("DataManagementService"))
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with StreamLogging {

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val ec: ExecutionContext = system.dispatcher

  val schedulerId = SchedulerInstanceId("scheduler0")
  val instanceId = schedulerId
  val leaseService = TestProbe()
  val watcherName = "data-management-service"
  leaseService.setAutoPilot((sender: ActorRef, msg: Any) =>
    msg match {
      case GetLease =>
        sender ! Lease(10, 10)
        TestActor.KeepRunning

      case _ =>
        TestActor.KeepRunning
  })

  private def etcdWorkerFactory(actor: ActorRef) = { (_: ActorRefFactory) =>
    actor
  }

  behavior of "DataManagementService"

  it should "distribute work to etcd worker" in {
    val watcherService = TestProbe()
    val worker = TestProbe()

    val key = "testKey"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))

    val requests = Seq(
      RegisterData(key, value),
      ElectLeader(key, value, self),
      RegisterInitialData(key, value, recipient = Some(testActor)),
      WatcherClosed(key, false))

    requests.foreach { request =>
      service ! request
      worker.expectMsg(request)

      service ! FinishWork(key)
    }
  }

  it should "handle request sequentially for a same key" in {
    val queue = mutable.Queue.empty[String]
    val watcherService = TestProbe()
    val workerFactory = (f: ActorRefFactory) =>
      f.actorOf(Props(new Actor {
        override def receive: Receive = {
          case request: RegisterData =>
            if (request.value == "async")
              Future {
                Thread.sleep(1000)
                queue.enqueue(request.value)
                context.parent ! FinishWork(request.key)
              } else {
              queue.enqueue(request.value)
              context.parent ! FinishWork(request.key)
            }
        }
      }))

    val key = "testKey"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, workerFactory))

    // the first request will be handled asynchronously, but as the second request has the same key, it will always
    // processed after the first request is finished
    val requests = Seq(RegisterData(key, "async"), RegisterData(key, value))

    requests.foreach { request =>
      service ! request
    }

    Thread.sleep(2000) // wait for two requests are completed
    queue.dequeue() shouldBe "async"
    queue.dequeue() shouldBe value // the second request should be wait for the first one finished
    queue.size shouldBe 0
  }

  it should "handle request concurrently for different keys" in {
    val queue = mutable.Queue.empty[String]
    val watcherService = TestProbe()
    val workerFactory = (f: ActorRefFactory) =>
      f.actorOf(Props(new Actor {
        override def receive: Receive = {
          case request: RegisterData =>
            if (request.value == "async")
              Future {
                Thread.sleep(1000)
                queue.enqueue(request.value)
                context.parent ! FinishWork(request.key)
              } else {
              queue.enqueue(request.value)
              context.parent ! FinishWork(request.key)
            }
        }
      }))

    val key = "testKey"
    val key2 = "testKey2"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, workerFactory))

    val requests = Seq(RegisterData(key, "async"), RegisterData(key2, value))

    requests.foreach { request =>
      service ! request
    }

    Thread.sleep(2000) // wait for two requests are completed
    queue.dequeue() shouldBe value // the second request should be completed first because it doesn't wait
    queue.dequeue() shouldBe "async"
    queue.size shouldBe 0
  }

  it should "remove unnecessary operation" in {
    val watcherService = TestProbe()
    val worker = TestProbe()

    val key = "testKey"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))

    service ! RegisterData(key, value) // occupy the resource
    worker.expectMsg(RegisterData(key, value))

    service ! RegisterInitialData(key, value) // this request should also be removed

    val requests = Random.shuffle(
      Seq(RegisterData(key, value), RegisterData(key, value), WatcherClosed(key, false), WatcherClosed(key, false)))
    // below requests will be merged into one request and wait in the queue
    requests.foreach { request =>
      service ! request
    }
    worker.expectNoMessage()

    service ! FinishWork(key) // release the resource

    worker.expectMsg(requests(3)) // only the last one should be distributed
  }

  it should "register data when the target endpoint is removed" in {
    val watcherService = TestProbe()
    val worker = TestProbe()
    val key = "testKey"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))

    // no new watcher is registered as it assumes the one is already registered.
    service ! WatchEndpointRemoved(key, key, value, isPrefix = false)
    worker.expectMsg(RegisterInitialData(key, value, false, None))
  }

  it should "ignore prefixed endpoint-removed results" in {
    val watcherService = TestProbe()
    val worker = TestProbe()
    val key = "testKey"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))
    service ! WatchEndpointRemoved("", key, value, isPrefix = true)

    worker.expectNoMessage()
  }

  it should "deregister data" in {
    val watcherService = TestProbe()
    val worker = TestProbe()
    watcherService.setAutoPilot((sender, msg) => {
      msg match {
        case UnwatchEndpoint(key, isPrefix, _, _) =>
          sender ! WatcherClosed(key, isPrefix)
          TestActor.KeepRunning
      }
    })
    val key = "testKey"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))
    service ! UnregisterData(key)

    watcherService.expectMsg(UnwatchEndpoint(key, isPrefix = false, watcherName, true))
    worker.expectMsg(WatcherClosed(key, false))
  }

  it should "store the resource data" in {
    val watcherService = TestProbe()
    val worker = TestProbe()
    val key = "testKey"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))
    service ! UpdateDataOnChange(key, value)

    worker.expectMsg(RegisterData(key, value))
    service.underlyingActor.dataCache.size shouldBe 1
  }

  it should "not store the resource data if there is no change from the last one" in {
    val watcherService = TestProbe()
    val worker = TestProbe()
    val key = "testKey"
    val value = "testValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))
    service ! UpdateDataOnChange(key, value)

    worker.expectMsg(RegisterData(key, value))
    service.underlyingActor.dataCache.size shouldBe 1

    service ! UpdateDataOnChange(key, value)
    worker.expectNoMessage()
    service.underlyingActor.dataCache.size shouldBe 1
  }

  it should "store the resource data if there is change from the last one" in {
    val watcherService = TestProbe()
    val worker = TestProbe()
    val key = "testKey"
    val value = "testValue"
    val newValue = "newTestValue"

    val service = TestActorRef(new DataManagementService(watcherService.ref, etcdWorkerFactory(worker.ref)))
    service ! UpdateDataOnChange(key, value)

    worker.expectMsg(RegisterData(key, value))
    service.underlyingActor.dataCache.size shouldBe 1

    service ! FinishWork(key)
    service ! UpdateDataOnChange(key, newValue)
    worker.expectMsg(RegisterData(key, newValue, false))
    service.underlyingActor.dataCache.size shouldBe 1
  }
}
