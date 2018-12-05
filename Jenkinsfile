#!/usr/bin/env groovy

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

timeout(time: 4, unit: 'HOURS') {

    // This node name will be changed to openwhisk1, when it is applied to the Apache environment.
    node("openwhisk1") {
        deleteDir()

        stage('Checkout') {
            echo 'Checking out the source code.'
            checkout([$class: 'GitSCM',
                      branches: [[name: "*/${Branch}"]],
                      doGenerateSubmoduleConfigurations: false,
                      extensions: [
                              [$class: 'CloneOption', noTags: true, reference: '', shallow: true],
                              [$class: 'CleanBeforeCheckout']
                      ],
                      submoduleCfg: [],
                      userRemoteConfigs: [[url: "https://github.com/${Fork}/${RepoName}.git"]]
            ])
        }
        sh 'pwd'

        stage('Build') {
            echo 'Building....'
        }

        stage('Deploy') {
            echo 'Deploying....'
        }

        stage('Test') {
            echo 'Testing....'
        }
        sh 'pwd'
    }

    // There are totally 3 VMs available for OpenWhisk in apache. The other two will be used later.
    /*node("openwhisk2") {
        checkout scm
        sh 'pwd'
        sh 'cat Jenkinsfile'
    }

    node("openwhisk3") {
        checkout scm
        sh 'pwd'
        sh 'cat Jenkinsfile'
    }*/

}
