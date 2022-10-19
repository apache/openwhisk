package org.apache.openwhisk.core.connector.test

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.apache.openwhisk.common.InvokerState.{Healthy, Unhealthy}
import org.apache.openwhisk.core.connector.InvokerResourceMessage
import org.apache.openwhisk.core.entity.SchedulerInstanceId
import org.apache.openwhisk.core.scheduler.{SchedulerEndpoints, SchedulerStates}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpecLike, Matchers}

@RunWith(classOf[JUnitRunner])
class MessageTests extends TestKit(ActorSystem("Message")) with FlatSpecLike with Matchers {
  behavior of "Message"

  it should "be able to compare the InvokerResourceMessage" in {
    val msg1 = InvokerResourceMessage(Unhealthy.asString, 1024L, 0, 0, Seq.empty, Seq.empty)
    val msg2 = InvokerResourceMessage(Unhealthy.asString, 1024L, 0, 0, Seq.empty, Seq.empty)

    msg1 == msg2 shouldBe true
  }

  it should "be different when the state of InvokerResourceMessage is different" in {
    val msg1 = InvokerResourceMessage(Unhealthy.asString, 1024L, 0, 0, Seq.empty, Seq.empty)
    val msg2 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq.empty, Seq.empty)

    msg1 != msg2 shouldBe true
  }

  it should "be different when the free memory of InvokerResourceMessage is different" in {
    val msg1 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq.empty, Seq.empty)
    val msg2 = InvokerResourceMessage(Healthy.asString, 2048L, 0, 0, Seq.empty, Seq.empty)

    msg1 != msg2 shouldBe true
  }

  it should "be different when the busy memory of InvokerResourceMessage is different" in {
    val msg1 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq.empty, Seq.empty)
    val msg2 = InvokerResourceMessage(Healthy.asString, 1024L, 1024L, 0, Seq.empty, Seq.empty)

    msg1 != msg2 shouldBe true
  }

  it should "be different when the in-progress memory of InvokerResourceMessage is different" in {
    val msg1 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq.empty, Seq.empty)
    val msg2 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 1024L, Seq.empty, Seq.empty)

    msg1 != msg2 shouldBe true
  }

  it should "be different when the tags of InvokerResourceMessage is different" in {
    val msg1 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq("tag1"), Seq.empty)
    val msg2 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq("tag1", "tag2"), Seq.empty)

    msg1 != msg2 shouldBe true
  }

  it should "be different when the dedicated namespaces of InvokerResourceMessage is different" in {
    val msg1 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq.empty, Seq("ns1"))
    val msg2 = InvokerResourceMessage(Healthy.asString, 1024L, 0, 0, Seq.empty, Seq("ns2"))

    msg1 != msg2 shouldBe true
  }

  it should "be able to compare the SchedulerStates" in {
    val msg1 = SchedulerStates(SchedulerInstanceId("0"), queueSize = 0, SchedulerEndpoints("10.10.10.10", 1234, 1234))
    val msg2 = SchedulerStates(SchedulerInstanceId("0"), queueSize = 0, SchedulerEndpoints("10.10.10.10", 1234, 1234))

    msg1 == msg2 shouldBe true
  }

  it should "be different when the queue size of SchedulerStates is different" in {
    val msg1 = SchedulerStates(SchedulerInstanceId("0"), queueSize = 20, SchedulerEndpoints("10.10.10.10", 1234, 1234))
    val msg2 = SchedulerStates(SchedulerInstanceId("0"), queueSize = 10, SchedulerEndpoints("10.10.10.10", 1234, 1234))

    msg1 != msg2 shouldBe true
  }

  it should "be not different when other than the queue size of SchedulerStates is different" in {
    // only the queue size matter
    val msg1 = SchedulerStates(SchedulerInstanceId("0"), queueSize = 20, SchedulerEndpoints("10.10.10.10", 1234, 1234))
    val msg2 = SchedulerStates(SchedulerInstanceId("1"), queueSize = 20, SchedulerEndpoints("10.10.10.20", 5678, 5678))

    msg1 == msg2 shouldBe true
  }
}
