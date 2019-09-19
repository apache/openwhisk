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

package org.apache.openwhisk.standalone

import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import kafka.server.KafkaConfig
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.commons.io.FileUtils
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.WhiskConfig.kafkaHosts
import org.apache.openwhisk.core.entity.ControllerInstanceId
import org.apache.openwhisk.core.loadBalancer.{LeanBalancer, LoadBalancer, LoadBalancerProvider}
import org.apache.openwhisk.standalone.StandaloneDockerSupport.{checkOrAllocatePort, containerName, createRunCmd}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.io.Directory
import scala.util.Try

class KafkaLauncher(docker: StandaloneDockerClient,
                    kafkaPort: Int,
                    kafkaDockerPort: Int,
                    zkPort: Int,
                    workDir: File,
                    kafkaUi: Boolean)(implicit logging: Logging,
                                      ec: ExecutionContext,
                                      actorSystem: ActorSystem,
                                      materializer: ActorMaterializer,
                                      tid: TransactionId) {

  def run(): Future[Seq[ServiceContainer]] = {
    for {
      kafkaSvcs <- runKafka()
      uiSvcs <- if (kafkaUi) runKafkaUI() else Future.successful(Seq.empty[ServiceContainer])
    } yield kafkaSvcs ++ uiSvcs
  }

  def runKafka(): Future[Seq[ServiceContainer]] = {

    //Below setting based on https://rmoff.net/2018/08/02/kafka-listeners-explained/
    // We configure two listeners where one is used for host based application and other is used for docker based application
    // to connect to Kafka server running on host
    // Here controller / invoker will use LISTENER_LOCAL since they run in the same JVM as the embedded Kafka
    // and Kafka UI will run in a Docker container and use LISTENER_DOCKER
    val brokerProps = Map(
      KafkaConfig.ListenersProp -> s"LISTENER_LOCAL://localhost:$kafkaPort,LISTENER_DOCKER://localhost:$kafkaDockerPort",
      KafkaConfig.AdvertisedListenersProp -> s"LISTENER_LOCAL://localhost:$kafkaPort,LISTENER_DOCKER://${StandaloneDockerSupport
        .getLocalHostIp()}:$kafkaDockerPort",
      KafkaConfig.ListenerSecurityProtocolMapProp -> "LISTENER_LOCAL:PLAINTEXT,LISTENER_DOCKER:PLAINTEXT",
      KafkaConfig.InterBrokerListenerNameProp -> "LISTENER_LOCAL")
    implicit val config: EmbeddedKafkaConfig =
      EmbeddedKafkaConfig(kafkaPort = kafkaPort, zooKeeperPort = zkPort, customBrokerProperties = brokerProps)

    val t = Try {
      EmbeddedKafka.startZooKeeper(createDir("zookeeper"))
      EmbeddedKafka.startKafka(createDir("kafka"))
    }

    Future
      .fromTry(t)
      .map(
        _ =>
          Seq(
            ServiceContainer(kafkaPort, s"localhost:$kafkaPort", "kafka"),
            ServiceContainer(
              kafkaDockerPort,
              s"${StandaloneDockerSupport.getLocalHostIp()}:$kafkaDockerPort",
              "kafka-docker"),
            ServiceContainer(zkPort, "Zookeeper", "zookeeper")))
  }

  def runKafkaUI(): Future[Seq[ServiceContainer]] = {
    val hostIp = StandaloneDockerSupport.getLocalHostIp()
    val port = checkOrAllocatePort(9000)
    val env = Map(
      "ZOOKEEPER_CONNECT" -> s"$hostIp:$zkPort",
      "KAFKA_BROKERCONNECT" -> s"$hostIp:$kafkaDockerPort",
      "JVM_OPTS" -> "-Xms32M -Xmx64M",
      "SERVER_SERVLET_CONTEXTPATH" -> "/")

    logging.info(this, s"Starting Kafka Drop UI port: $port")
    val name = containerName("kafka-drop-ui")
    val params = Map("-p" -> Set(s"$port:9000"))
    val args = createRunCmd(name, env, params)

    val f = docker.runDetached("obsidiandynamics/kafdrop", args, true)
    f.map(_ => Seq(ServiceContainer(port, s"http://localhost:$port", name)))
  }

  private def createDir(name: String) = {
    val dir = new File(workDir, name)
    FileUtils.forceMkdir(dir)
    Directory(dir)
  }
}

object KafkaAwareLeanBalancer extends LoadBalancerProvider {
  override def requiredProperties: Map[String, String] = LeanBalancer.requiredProperties ++ kafkaHosts

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = LeanBalancer.instance(whiskConfig, instance)
}

object KafkaLauncher {
  val preferredKafkaPort = 9092
  val preferredKafkaDockerPort = preferredKafkaPort - 1
  val preferredZkPort = 2181
}
