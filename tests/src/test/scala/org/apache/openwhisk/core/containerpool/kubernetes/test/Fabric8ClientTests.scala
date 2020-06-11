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

package org.apache.openwhisk.core.containerpool.kubernetes.test

import java.net.HttpURLConnection

import common.{StreamLogging, WskActorSystem}
import io.fabric8.kubernetes.api.model.{EventBuilder, PodBuilder}
import io.fabric8.kubernetes.client.utils.HttpClientUtils.createHttpClientForMockServer
import io.fabric8.kubernetes.client.{ConfigBuilder, DefaultKubernetesClient}
import okhttp3.TlsVersion.TLS_1_0
import org.apache.openwhisk.common.{ConfigMapValue, TransactionId}
import org.apache.openwhisk.core.containerpool.kubernetes._
import org.apache.openwhisk.core.entity.size._
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class Fabric8ClientTests
    extends FlatSpec
    with Matchers
    with WskActorSystem
    with ScalaFutures
    with KubeClientSupport
    with StreamLogging {
  implicit val tid: TransactionId = TransactionId.testing
  behavior of "Fabric8Client"
  val runTimeout = 2.seconds
  def config(configMap: Option[ConfigMapValue] = None, affinity: Option[KubernetesInvokerNodeAffinity] = None) =
    KubernetesClientConfig(
      KubernetesClientTimeoutConfig(runTimeout, 2.seconds),
      affinity.getOrElse(KubernetesInvokerNodeAffinity(false, "k", "v")),
      false,
      None,
      configMap,
      Some(KubernetesCpuScalingConfig(300, 3.MB, 1000)),
      false,
      Some(Map("POD_UID" -> "metadata.uid")),
      None)

  it should "fail activation on cold start when apiserver fails" in {

    //use an invalid client to simulate broken api server
    def defaultClient = {
      val config = new ConfigBuilder()
        .withMasterUrl("http://localhost:11111") //test assumes that port 11111 will fail in some way
        .withTrustCerts(true)
        .withTlsVersions(TLS_1_0)
        .withNamespace("test")
        .build
      new DefaultKubernetesClient(createHttpClientForMockServer(config), config)
    }
    val restClient = new KubernetesClient(config(), testClient = Some(defaultClient))(executionContext)
    restClient.run("fail", "fail", 256.MB).failed.futureValue shouldBe a[KubernetesPodApiException]
  }
  it should "fail activation on cold start when pod ready times out" in {
    val podName = "failWait"
    server
      .expect()
      .post()
      .withPath("/api/v1/namespaces/test/pods")
      .andReturn(
        HttpURLConnection.HTTP_CREATED,
        new PodBuilder().withNewMetadata().withName(podName).endMetadata().build())
      .once();
    server
      .expect()
      .get()
      .withPath("/api/v1/namespaces/test/pods/failWait")
      .andReturn(HttpURLConnection.HTTP_OK, new PodBuilder().withNewMetadata().withName(podName).endMetadata().build())
      .times(3)
    server
      .expect()
      .get()
      .withPath("/api/v1/namespaces/test/events?fieldSelector=involvedObject.name%3DfailWait")
      .andReturn(
        HttpURLConnection.HTTP_OK,
        new EventBuilder().withNewMetadata().withName(podName).endMetadata().build())
      .once

    implicit val patienceConfig = PatienceConfig(timeout = runTimeout + 1.seconds, interval = 0.5.seconds)

    val restClient = new KubernetesClient(config(), testClient = Some(kubeClient))(executionContext)
    restClient.run(podName, "anyimage", 256.MB).failed.futureValue shouldBe a[KubernetesPodReadyTimeoutException]
  }
}
