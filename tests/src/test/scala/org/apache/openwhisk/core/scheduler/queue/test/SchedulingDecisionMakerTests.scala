package org.apache.openwhisk.core.scheduler.queue.test

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import common.StreamLogging
import org.apache.openwhisk.core.entity.{EntityName, EntityPath, FullyQualifiedEntityName, SemVer}
import org.apache.openwhisk.core.scheduler.queue._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpecLike, Matchers}

class SchedulingDecisionMakerTests
    extends TestKit(ActorSystem("SchedulingDecisionMakerTests"))
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with StreamLogging {

  behavior of "SchedulingDecisionMaker"

  implicit val ec = system.dispatcher

  val testNamespace = "test-namespace"
  val testAction = "test-action"
  val action = FullyQualifiedEntityName(EntityPath(testNamespace), EntityName(testAction), Some(SemVer(0, 0, 1)))

  it should "decide pausing when the limit is less than equal to 0" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 0,
      inProgressContainerCountInNamespace = 0,
      averageDuration = None,
      limit = 0, // limit is 0
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(Pausing, 0))
  }

  it should "skip decision if the state is already Flushing when the limit is <= 0" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 0,
      inProgressContainerCountInNamespace = 0,
      averageDuration = None,
      limit = 0, // limit is 0
      stateName = Flushing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectNoMessage()
  }

  it should "skip decision at any time if there is no message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))

    // For Throttled states, there will be always at least one message to disable the throttling
    val states = List(Running, Idle, Flushing, Removing, Removed)
    val testProbe = TestProbe()

    states.foreach { state =>
      val msg = QueueSnapshot(
        initialized = false,
        incomingMsgCount = new AtomicInteger(0),
        currentMsgCount = 0,
        existingContainerCount = 1,
        inProgressContainerCount = 2,
        staleActivationNum = 0,
        existingContainerCountInNamespace = 1,
        inProgressContainerCountInNamespace = 2,
        averageDuration = None, // No average duration available
        limit = 10,
        stateName = state,
        recipient = testProbe.ref)

      decisionMaker ! msg

      testProbe.expectNoMessage()
    }
  }

  it should "skip decision at any time if there is no message even with avg duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val states = List(Running, Idle, Flushing, Removing, Removed)
    val testProbe = TestProbe()

    states.foreach { state =>
      val msg = QueueSnapshot(
        initialized = false,
        incomingMsgCount = new AtomicInteger(0),
        currentMsgCount = 0,
        existingContainerCount = 3,
        inProgressContainerCount = 5,
        staleActivationNum = 0,
        existingContainerCountInNamespace = 5,
        inProgressContainerCountInNamespace = 8,
        averageDuration = Some(1.0), // Some average duration available
        limit = 20,
        stateName = state,
        recipient = testProbe.ref)

      decisionMaker ! msg

      testProbe.expectNoMessage()
    }
  }

  it should "enable namespace throttling with dropping msg when there is not enough capacity and no container" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0, // there is no container for this action
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 2,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create an initial container so enable throttling and drop messages.
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = true), 0))
  }

  it should "enable namespace throttling without dropping msg when there is not enough capacity but are some containers" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 1, // there are some containers for this action
      inProgressContainerCount = 1,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 2, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 2, // this value includes the count of this action as well.
      averageDuration = None,
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create more containers
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 0))
  }

  it should "add an initial container if there is no any" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false, // this queue is not initialized yet
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddInitialContainer, 1))
  }
  it should "disable the namespace throttling with adding an initial container when there is no container" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false, // this queue is not initialized yet
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 1,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = NamespaceThrottled,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(DisableNamespaceThrottling, 0))
  }

  it should "disable the namespace throttling when there are some containers" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false, // this queue is not initialized yet
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 1,
      inProgressContainerCount = 1,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = NamespaceThrottled,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(DisableNamespaceThrottling, 0))
  }

  // this is an exceptional case
  it should "add an initial container if there is no container in the Running state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(1), // if there is no message, it won't add a container.
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 1))
  }

  // this is an exceptional case
  it should "not add a container if there is no message even in the Running state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0), // if there is no message, it won't add a container.
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectNoMessage()
  }

  // this can happen when the limit was 0 for some reason previously but it is increased after some time.
  it should "add one container if there is no container in the Paused state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0), // it should add a container even if there is no message this is because all messages are dropped in the Paused state
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Flushing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddInitialContainer, 1))
  }

  it should "add one container if there is no container in the Waiting state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0), // it should add a container even if there is no message this is because all messages are dropped in the Waiting state
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Flushing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddInitialContainer, 1))
  }

  it should "add same number of containers with the number of stale messages if there are any" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 2))
  }

  it should "add exclude the number of in-progress container when adding containers for stale messages when there is no available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 1,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 1))
  }

  it should "add at most the same number with the limit when adding containers for stale messages when there is no available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 6,
      existingContainerCount = 1,
      inProgressContainerCount = 1,
      staleActivationNum = 6,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 2))
  }

  it should "not add any container for stale messages if the increment is <= 0 when there when there is no available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 6,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 6,
      averageDuration = None,
      limit = 10,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectNoMessage()
  }

  it should "add containers for stale messages based on duration when there is available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 6,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 6, // stale messages exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(50), // the average duration exists
      limit = 10,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 50 = 3
    testProbe.expectMsg(DecisionResults(AddContainer, 3))
  }

  it should "add containers for stale messages at most the number of messages when there is available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 2, // stale messages exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      limit = 10,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 2 / 100 / 1000 = 20, but add only 2 containers for 2 messages
    testProbe.expectMsg(DecisionResults(AddContainer, 2))
  }

  it should "add containers for stale messages within the limit when there is available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 10,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 10, // stale messages exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 10 / 100 / 1000 = 100, but because there is only 10 messages, it is supposed to be 10.
    // Finally it becomes it becomes 3 because there are not enough capacity.
    // limit - total containers in a namespace = 4 - 1 = 3
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 3))
  }

  it should "add containers based on duration if there is no stale message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(4),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      limit = 10,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 5 because there is already one container.
    testProbe.expectMsg(DecisionResults(AddContainer, 5))
  }

  it should "add containers based on duration within the capacity if there is no stale message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(4),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      limit = 4,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 3 because there is not enough capacity
    // limit - containers in a namespace = 4 - 1 = 3
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 3))
  }

  it should "not add container when expected TPS is bigger than available message if there is no stale message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 5,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 5,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      limit = 10,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 5 because there is already one container.
    testProbe.expectNoMessage()
  }

  it should "add one container when there is no container and are some messages" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 0,
      inProgressContainerCountInNamespace = 0,
      averageDuration = None, // the average duration exists
      limit = 10,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 1))
  }

  it should "add more containers when there are stale messages even in the GracefulShuttingDown state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 1, // some containers are running and in-progress
      inProgressContainerCount = 1,
      staleActivationNum = 4, // stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = Some(1000), // the average duration exists
      limit = 10,
      stateName = Removing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 4 / 100 / 1000 = 40, but because there is only 4 messages, it is supposed to be 4.
    // Finally it becomes it becomes 3 because there is not enough capacity.
    testProbe.expectMsg(DecisionResults(AddContainer, 3))
  }

  it should "add more containers when there are stale messages even in the GracefulShuttingDown state when there is no duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 1,
      inProgressContainerCount = 2, // some containers are in progress
      staleActivationNum = 4, // stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 2,
      averageDuration = None, // the average duration does not exist
      limit = 10,
      stateName = Removing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 5 because there is already one container.
    testProbe.expectMsg(DecisionResults(AddContainer, 2))
  }

  it should "enable namespace throttling while adding more container when there are stale messages even in the GracefulShuttingDown" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 1,
      inProgressContainerCount = 2, // some containers are in progress
      staleActivationNum = 4, // stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 2,
      averageDuration = None, // the average duration does not exist
      limit = 4,
      stateName = Removing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 4 / 100 / 1000 = 40, but because there is only 4 messages, it is supposed to be 4.
    // it should subtract the in-progress number so it is supposed to be 2
    // but there is not enough capacity, it becomes 1
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(false), 1))
  }
}
