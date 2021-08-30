package org.apache.openwhisk.core.scheduler.queue.test

import java.time.Instant

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.sksamuel.elastic4s.http
import com.sksamuel.elastic4s.http.ElasticDsl.{avgAgg, boolQuery, matchQuery, rangeQuery, search}
import com.sksamuel.elastic4s.http._
import com.sksamuel.elastic4s.http.search.{SearchHits, SearchResponse}
import com.sksamuel.elastic4s.searches.SearchRequest
import common.StreamLogging
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.common.WhiskInstants.InstantImplicits
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, ActivationMessage, Message, MessageProducer}
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.database.elasticsearch.ElasticSearchActivationStore.generateIndex
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity.{WhiskActivation, _}
import org.apache.openwhisk.core.etcd.EtcdKV.{ContainerKeys, QueueKeys, ThrottlingKeys}
import org.apache.openwhisk.core.scheduler.SchedulerEndpoints
import org.apache.openwhisk.core.scheduler.grpc.GetActivation
import org.apache.openwhisk.core.scheduler.queue.ElasticSearchDurationChecker.{getFromDate, AverageAggregationName}
import org.apache.openwhisk.core.scheduler.queue._
import org.apache.openwhisk.core.service.{
  AlreadyExist,
  DeleteEvent,
  Done,
  InitialDataStorageResults,
  PutEvent,
  RegisterData,
  RegisterInitialData,
  UnregisterData,
  UnwatchEndpoint,
  WatchEndpoint
}
import org.scalamock.scalatest.MockFactory

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.{higherKinds, postfixOps}

class MemoryQueueTestsFixture
    extends TestKit(ActorSystem("MemoryQueue"))
    with MockFactory
    with ImplicitSender
    with StreamLogging {

  implicit val ece: ExecutionContextExecutor = system.dispatcher

  // common variables
  def requiredProperties =
    Map(
      WhiskConfig.actionInvokePerMinuteLimit -> null,
      WhiskConfig.actionInvokeConcurrentLimit -> null,
      WhiskConfig.triggerFirePerMinuteLimit -> null)
  val config = new WhiskConfig(requiredProperties)

  // action variables
  val testInvocationNamespace = "test-invocation-namespace"
  val testNamespace = "test-namespace"
  val testAction = "test-action"

  val fqn = FullyQualifiedEntityName(EntityPath(testNamespace), EntityName(testAction), Some(SemVer(0, 0, 1)))
  val revision = DocRevision("1-testRev")
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val action = ExecutableWhiskAction(EntityPath(testNamespace), EntityName(testAction), exec)
  val execMetadata =
    CodeExecMetaDataAsString(RuntimeManifest(action.exec.kind, ImageName("test")), entryPoint = Some("test"))
  val actionMetadata =
    WhiskActionMetaData(
      action.namespace,
      action.name,
      execMetadata,
      action.parameters,
      action.limits,
      action.version,
      action.publish,
      action.annotations)
      .revision[WhiskActionMetaData](action.rev)
  val dummyWhiskActivation = WhiskActivation(
    EntityPath("testnamespace"),
    EntityName("activation"),
    Subject(),
    ActivationId.generate(),
    start = Instant.now.inMills,
    end = Instant.now.inMills)

  val messageTransId = TransactionId(TransactionId.testing.meta.id)
  val uuid = UUID()
  val message = ActivationMessage(
    messageTransId,
    action.fullyQualifiedName(true),
    action.rev,
    Identity(
      Subject(),
      Namespace(EntityName(testInvocationNamespace), uuid),
      BasicAuthenticationAuthKey(uuid, Secret()),
      Set.empty),
    ActivationId.generate(),
    ControllerInstanceId("0"),
    blocking = false,
    content = None)

  // scheduler variables
  val schedulerId = SchedulerInstanceId("0")
  val endpoints = SchedulerEndpoints("127.0.0.1", 2552, 8080)

  // elasticsearch
  val durationCheckWindow = 1.days
  val mockEsClient = mock[ElasticClient]
  val durationChecker = new ElasticSearchDurationChecker(mockEsClient, durationCheckWindow)

  // ETCD variables
  val leaderKey = QueueKeys.queue(testInvocationNamespace, fqn, leader = true)
  val leaderValue: String = endpoints.serialize

  val inProgressContainerKey =
    ContainerKeys.containerPrefix(ContainerKeys.inProgressPrefix, testInvocationNamespace, fqn, Some(revision))
  val existingContainerKey =
    ContainerKeys.containerPrefix(ContainerKeys.namespacePrefix, testInvocationNamespace, fqn, Some(revision))
  val inProgressContainerPrefixKeyByNamespace =
    ContainerKeys.inProgressContainerPrefixByNamespace(testInvocationNamespace)
  val existingContainerPrefixKeyByNamespace =
    ContainerKeys.existingContainersPrefixByNamespace(testInvocationNamespace)
  val namespaceThrottlingKey = ThrottlingKeys.namespace(EntityName(testInvocationNamespace))
  val actionThrottlingKey = ThrottlingKeys.action(testInvocationNamespace, fqn.copy(version = None))

  // queue variables
  val queueConfig = QueueConfig(5 seconds, 10 seconds, 5 seconds, 5 seconds, 10, 10000, 0.9, 10)
  val idleGrace = queueConfig.idleGrace
  val stopGrace = queueConfig.stopGrace
  val flushGrace = queueConfig.flushGrace
  val gracefulShutdownTimeout = queueConfig.gracefulShutdownTimeout
  val testRetentionSize = queueConfig.maxRetentionSize
  val testThrottlingFraction = queueConfig.throttlingFraction
  val queueRemovedMsg = QueueRemoved(testInvocationNamespace, fqn.toDocId.asDocInfo(revision), Some(leaderKey))
  val staleQueueRemovedMsg = QueueRemoved(testInvocationNamespace, fqn.toDocId.asDocInfo(revision), None)
  val queueReactivatedMsg = QueueReactivated(testInvocationNamespace, fqn, fqn.toDocId.asDocInfo(revision))

  // DataManagementService
  val testInitialDataStorageResult = InitialDataStorageResults(leaderKey, Right(Done()))
  val failedInitialDataStorageResult = InitialDataStorageResults(leaderKey, Left(AlreadyExist()))

  // Watcher
  val watcherName = s"memory-queue-$fqn-$revision"
  val watcherNameForNamespace = s"container-counter-$testInvocationNamespace"
  val testCreationId = CreationId.generate()

  // ack
  var ackedMessageCount = 0
  var lastAckedActivationResult = dummyWhiskActivation
  val ack = new ActiveAck {
    override def apply(tid: TransactionId,
                       activationResult: WhiskActivation,
                       blockingInvoke: Boolean,
                       controllerInstance: ControllerInstanceId,
                       userId: UUID,
                       acknowledegment: AcknowledegmentMessage): Future[Any] = {
      ackedMessageCount += 1
      lastAckedActivationResult = activationResult
      Future.successful({})
    }
  }

  // store activation
  var storedMessageCount = 0
  var lastStoredActivationResult = dummyWhiskActivation
  val store: (TransactionId, WhiskActivation, UserContext) => Future[Any] =
    (tid: TransactionId, activationResult: WhiskActivation, contest: UserContext) => {
      storedMessageCount += 1
      lastStoredActivationResult = activationResult
      Future.successful(())
    }

  def mockMessaging(receiver: Option[ActorRef] = None): MessageProducer = {
    val producer = receiver.map(fakeProducer(_)).getOrElse(stub[MessageProducer])
    producer
  }

  private def fakeProducer(receiver: ActorRef) = new MessageProducer {

    /** Count of messages sent. */
    override def sentCount(): Long = 0

    /** Sends msg to topic. This is an asynchronous operation. */
    override def send(topic: String, msg: Message, retry: Int): Future[RecordMetadata] = {
      receiver ! s"$topic-${msg}"

      Future.successful(
        new RecordMetadata(new TopicPartition(topic, 0), -1, -1, System.currentTimeMillis(), null, -1, -1))
    }

    /** Closes producer. */
    override def close(): Unit = {}
  }

  def getUserLimit(invocationNamespace: String): Future[Int] = {
    Future.successful(2)
  }

  def expectDurationChecking(mockEsClient: ElasticClient, namespace: String) = {
    val index = generateIndex(namespace)

    val searchRequest = (search(index) query {
      boolQuery must {
        List(
          matchQuery("path.keyword", fqn.copy(version = None).toString),
          rangeQuery("@timestamp").gte(getFromDate(durationCheckWindow)))
      }
    } aggregations
      avgAgg(AverageAggregationName, "duration")).size(0)

    (mockEsClient
      .execute[SearchRequest, SearchResponse, Future](_: SearchRequest)(
        _: Functor[Future],
        _: http.Executor[Future],
        _: Handler[SearchRequest, SearchResponse],
        _: Manifest[SearchResponse]))
      .expects(searchRequest, *, *, *, *)
      .returns(
        Future.successful(RequestSuccess(
          200,
          None,
          Map.empty,
          SearchResponse(1, false, false, Map.empty, Shards(0, 0, 0), None, Map.empty, SearchHits(0, 0, Array.empty)))))
      .once()
  }

  def expectInitialData(watcher: TestProbe, dataMgmtService: TestProbe) = {
    watcher.expectMsgAllOf(
      WatchEndpoint(leaderKey, endpoints.serialize, isPrefix = false, watcherName, Set(DeleteEvent)),
      WatchEndpoint(inProgressContainerKey, "", isPrefix = true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerKey, "", isPrefix = true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        inProgressContainerPrefixKeyByNamespace,
        "",
        isPrefix = true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        existingContainerPrefixKeyByNamespace,
        "",
        isPrefix = true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)))

    dataMgmtService.expectMsg(RegisterInitialData(namespaceThrottlingKey, false.toString, failoverEnabled = false))
    dataMgmtService.expectMsg(RegisterData(actionThrottlingKey, false.toString, failoverEnabled = false))
  }

  def expectDataCleanUp(watcher: TestProbe, dataMgmtService: TestProbe) = {
    dataMgmtService.expectMsgAllOf(
      UnregisterData(leaderKey),
      UnregisterData(namespaceThrottlingKey),
      UnregisterData(actionThrottlingKey))

    watcher.expectMsgAllOf(
      UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
      UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
      UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))
  }

  def getActivationMessages(count: Int): List[ActivationMessage] = {
    val result = 1 to count map { _ =>
      ActivationMessage(
        messageTransId,
        action.fullyQualifiedName(true),
        action.rev,
        Identity(
          Subject(),
          Namespace(EntityName(testInvocationNamespace), uuid),
          BasicAuthenticationAuthKey(uuid, Secret()),
          Set.empty),
        ActivationId.generate(),
        ControllerInstanceId("0"),
        blocking = false,
        content = None)
    }
    result.toList
  }

  def getActivation(alive: Boolean = true, containerId: String = "testContainerId") =
    GetActivation(TransactionId("tid"), fqn, containerId, warmed = false, None, alive)

}
