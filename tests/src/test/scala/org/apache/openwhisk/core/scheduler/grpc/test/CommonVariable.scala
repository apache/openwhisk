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

package org.apache.openwhisk.core.scheduler.grpc.test

import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._

trait CommonVariable {
  val testInvocationNamespace = "test-invocation-namespace"
  val testInvocationEntityPath = EntityPath(testInvocationNamespace)
  val testNamespace = "test-namespace"
  val testEntityPath = EntityPath(testNamespace)
  val testAction = "test-fqn"
  val testEntityName = EntityName(testAction)
  val testDocRevision = DocRevision("1-test-revision")
  val testContainerId = "fakeContainerId"
  val semVer = SemVer(0, 1, 1)
  val testVersion = Some(semVer)
  val testFQN = FullyQualifiedEntityName(testEntityPath, testEntityName, testVersion)
  val testExec = CodeExecAsString(RuntimeManifest("nodejs:14", ImageName("testImage")), "testCode", None)
  val testExecMetadata =
    CodeExecMetaDataAsString(testExec.manifest, entryPoint = testExec.entryPoint)
  val testActionMetaData =
    WhiskActionMetaData(testEntityPath, testEntityName, testExecMetadata, version = semVer)
}
