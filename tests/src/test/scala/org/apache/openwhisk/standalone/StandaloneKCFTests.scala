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

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import common.WskProps
import org.apache.commons.io.FileUtils
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

  private val podTemplateFile = Files.createTempFile("whisk", null).toFile

  override val customConfig = {
    FileUtils.write(podTemplateFile, podTemplate, UTF_8)
    Some(s"""include classpath("standalone-kcf.conf")
         |
         |whisk {
         |  kubernetes {
         |    pod-template = "${podTemplateFile.toURI}"
         |  }
         |}""".stripMargin)
  }

  override def afterAll(): Unit = {
    checkPodState()
    super.afterAll()
    podTemplateFile.delete()
  }

  def checkPodState(): Unit = {
    val podList = kubeClient.pods().withLabel("launcher").list()
    podList.getItems.isEmpty shouldBe false
  }
}
