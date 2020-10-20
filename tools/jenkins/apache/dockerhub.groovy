#!groovy
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

node('ubuntu') {
  sh "env"
  sh "docker version"
  sh "docker info"

  checkout scm

  stage("Build and Deploy to DockerHub") {
    def JAVA_JDK_8=tool name: 'jdk_1.8_latest', type: 'hudson.model.JDK'
    withEnv(["Path+JDK=$JAVA_JDK_8/bin","JAVA_HOME=$JAVA_JDK_8"]) {
      withCredentials([usernamePassword(credentialsId: 'openwhisk_dockerhub', passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USER')]) {
          sh 'docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}'
      }
      def PUSH_CMD = "./gradlew :core:controller:distDocker :core:invoker:distDocker :core:standalone:distDocker :core:monitoring:user-events:distDocker :tools:ow-utils:distDocker :core:cosmos:cache-invalidator:distDocker -PdockerRegistry=docker.io -PdockerImagePrefix=openwhisk"
      def gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
      def shortCommit = gitCommit.take(7)
      sh "./gradlew clean"
      sh "${PUSH_CMD} -PdockerImageTag=1.0.0"
    }
  }

  stage("Clean") {
    sh "docker images"
    sh 'docker rmi -f $(docker images -f "reference=openwhisk/*" -q) || true'
    sh "docker images"
  }

  stage("Notify") {
    withCredentials([string(credentialsId: 'openwhisk_slack_token', variable: 'OPENWHISK_SLACK_TOKEN')]) {
      sh "curl -X POST --data-urlencode 'payload={\"channel\": \"#dev\", \"username\": \"whiskbot\", \"text\": \"OpenWhisk Docker Images build and posted to https://hub.docker.com/u/openwhisk by Jenkins job ${BUILD_URL}\", \"icon_emoji\": \":openwhisk:\"}' https://hooks.slack.com/services/${OPENWHISK_SLACK_TOKEN}"
    }

  }
}
