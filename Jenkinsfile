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

timeout(time: 12, unit: 'HOURS') {

    def port = "444"
    def cert = "domain.crt"
    def key = "domain.key"

    node("openwhisk") {
        def hostName = sh(returnStdout: true, script: 'hostname').trim()
        def domainName = hostName+".apache.org"
        def home = sh(returnStdout: true, script: 'echo $HOME').trim()
        def jobName = sh(returnStdout: true, script: 'echo $JOB_NAME').trim()
        def jobSpace = "${home}/jenkins-slave/workspace/${jobName}"

        lock("${hostName}") {
            sh "mkdir -p ${jobSpace}"
            dir("${jobSpace}") {
                try {
                    deleteDir()
                    stage('Checkout') {
                        checkout scm
                    }

                    stage('Build') {
                        // Set up a private docker registry service, accessed by all the OpenWhisk VMs.
                        try {
                            sh "docker container stop registry && docker container rm -v registry"
                        } catch (exp) {
                            println("Unable to stop and remove the container registry.")
                        }

                        sh "docker run -d --restart=always --name registry -v \"$HOME\"/certs:/certs \
                                -e REGISTRY_HTTP_ADDR=0.0.0.0:${port} -e REGISTRY_HTTP_TLS_CERTIFICATE=/certs/${cert} \
                                -e REGISTRY_HTTP_TLS_KEY=/certs/${key} -p ${port}:${port} registry:2"
                        // Build the controller and invoker images.
                        sh "./gradlew distDocker -PdockerRegistry=${domainName}:${port}"
                        //Install the various modules like standalone
                        sh "./gradlew install"
                    }

                    stage('Deploy Lean') {
                        dir("ansible") {
                            // Copy the jenkins ansible configuration under the directory ansible. This can make sure the SSH is used to
                            // access the VMs of invokers by the VM of the controller.
                            sh '[ -f "environments/jenkins/ansible_jenkins.cfg" ] && cp environments/jenkins/ansible_jenkins.cfg ansible.cfg'
                        }

                        dir("ansible/environments/jenkins") {
                            sh "cp ${hostName}.j2.ini hosts.j2.ini"
                        }

                        dir("ansible/environments/jenkins/group_vars") {
                            sh "cp ${hostName} all"
                        }

                        dir("ansible") {
                            sh 'ansible-playbook -i environments/jenkins setup.yml'
                            sh 'ansible-playbook -i environments/jenkins openwhisk.yml -e mode=clean'
                            sh 'ansible-playbook -i environments/jenkins apigateway.yml -e mode=clean'
                            sh 'ansible-playbook -i environments/jenkins couchdb.yml -e mode=clean'
                            sh 'ansible-playbook -i environments/jenkins couchdb.yml'
                            sh 'ansible-playbook -i environments/jenkins initdb.yml'
                            sh 'ansible-playbook -i environments/jenkins wipe.yml'
                            sh 'ansible-playbook -i environments/jenkins apigateway.yml'
                            sh 'ansible-playbook -i environments/jenkins openwhisk.yml -e lean=true'
                            sh 'ansible-playbook -i environments/jenkins properties.yml'
                            sh 'ansible-playbook -i environments/jenkins routemgmt.yml'
                            sh 'ansible-playbook -i environments/jenkins postdeploy.yml'
                        }
                    }

                    try {
                        stage('Test Lean Openwhisk') {
                            sh './gradlew :tests:test --tests system.basic.WskRestBasicTests -DtestResultsDirName=test-lean-openwhisk'
                        }
                    } catch (exp) {
                        println("Exception: " + exp)
                        error(exp)
                    }

                    stage('Deploy full Openwhisk') {
                        dir("ansible") {
                            sh 'ansible-playbook -i environments/jenkins openwhisk.yml -e mode=clean'
                            sh 'ansible-playbook -i environments/jenkins openwhisk.yml'
                        }
                    }

                    try {
                        stage('Test') {
                            sh './gradlew :tests:test -DtestResultsDirName=test-openwhisk'
                        }
                    } catch (exp) {
                        println("Exception:" + exp)
                    }

                    try {
                        stage('Shoot one invoker test') {
                            def folder = "ansible/environments/jenkins/group_vars"
                            def invoker1_node = sh(returnStdout: true,
                                    script: "grep invoker1_machine ${folder}/${hostName} | cut -d: -f2").trim()
                            sh "ssh -i ${home}/secret/openwhisk_key openwhisk@${invoker1_node} 'docker stop invoker1'"
                            sleep time: 1, unit: 'MINUTES'
                            sh './gradlew :tests:testShootInvoker -DtestResultsDirName=test-shoot-invoker'
                            sh "ssh -i ${home}/secret/openwhisk_key openwhisk@${invoker1_node} 'docker start invoker1'"
                        }
                    } catch (exp) {
                        println("Exception:" + exp)
                    }

                } catch (exp) {
                    println("Exception:" + exp)
		    error(exp)
                } finally {
                    println("Executing finally block")
                    stage('Clean up') {
                        dir("ansible") {
                            sh 'ansible-playbook -i environments/jenkins openwhisk.yml -e mode=clean'
                            sh 'ansible-playbook -i environments/jenkins apigateway.yml -e mode=clean'
                            sh 'ansible-playbook -i environments/jenkins couchdb.yml -e mode=clean'
                        }
                    }
                    step([$class: 'JUnitResultArchiver', testResults: '**/test*/**/TEST-*.xml'])
                }
            }
        }
    }
}
