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

import common.StreamLogging
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.{ConfigBuilder, DefaultKubernetesClient}
import org.scalatest.{BeforeAndAfterAll, Suite, TestSuite}

import scala.concurrent.duration._

trait KubeClientSupport extends TestSuite with BeforeAndAfterAll with StreamLogging {
  self: Suite =>

  protected def useMockServer = true

  protected lazy val (kubeClient, closeable) = {
    if (useMockServer) {
      val server = new KubernetesMockServer(false)
      server.init()
      (server.createClient(), () => server.destroy())
    } else {
      val client = new DefaultKubernetesClient(
        new ConfigBuilder()
          .withConnectionTimeout(1.minute.toMillis.toInt)
          .withRequestTimeout(1.minute.toMillis.toInt)
          .build())
      (client, () => client.close())
    }
  }

  override def beforeAll(): Unit = {
    if (!useMockServer) {
      val kubeconfig = sys.env.get("KUBECONFIG")
      assume(kubeconfig.isDefined, "KUBECONFIG env must be defined")
      println(s"Using kubeconfig from ${kubeconfig.get}")
    }
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    closeable.apply()
  }
}
