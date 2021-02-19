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
  val testExec = CodeExecAsString(RuntimeManifest("nodejs:10", ImageName("testImage")), "testCode", None)
  val testExecMetadata =
    CodeExecMetaDataAsString(testExec.manifest, entryPoint = testExec.entryPoint)
  val testActionMetaData =
    WhiskActionMetaData(testEntityPath, testEntityName, testExecMetadata, version = semVer)
}
