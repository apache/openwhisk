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

import common.WskProps
import org.apache.openwhisk.core.containerpool.kubernetes.test.KubeClientSupport
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import system.basic.WskRestBasicTests

@RunWith(classOf[JUnitRunner])
class StandaloneKCFTests
    extends WskRestBasicTests
    with StandaloneServerFixture
    with StandaloneSanityTestSupport
    with KubeClientSupport {
  override implicit val wskprops = WskProps().copy(apihost = serverUrl)

  val qt = "\"\"\""
  //Turn on to debug locally easily
  override protected val dumpLogsAlways = false

  override protected val dumpStartupLogs = false

  override protected def useMockServer = false

  override protected def supportedTests = Set("Wsk Action REST should invoke a blocking action and get only the result")

  override protected def extraArgs: Seq[String] = Seq("--dev-mode", "--dev-kcf")

  private val podTemplate = """---
                              |apiVersion: "v1"
                              |kind: "Pod"
                              |metadata:
                              |  annotations:
                              |    allow-outbound : "true"
                              |  labels:
                              |     launcher: standalone""".stripMargin

  override val customConfig = Some(s"""include classpath("standalone-kcf.conf")
     |
     |whisk {
     |  kubernetes {
     |    pod-template = $qt$podTemplate$qt
     |  }
     |}""".stripMargin)

  override def beforeAll(): Unit = {
    val kubeconfig = sys.env.get("KUBECONFIG")
    require(kubeconfig.isDefined, "KUBECONFIG env must be defined")
    println(s"Using kubeconfig from ${kubeconfig.get}")

    //Note the context need to specify default namespace
    //kubectl config set-context --current --namespace=default
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    checkPodState()
    super.afterAll()
  }

  def checkPodState(): Unit = {
    val podList = kubeClient.pods().withLabel("launcher").list()
    podList.getItems.isEmpty shouldBe false
  }
}
