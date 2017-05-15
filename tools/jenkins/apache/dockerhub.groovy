#!groovy
node("ubuntu&&xenial") {
  sh "env"
  sh "docker version"
  sh "docker info"

  stage("Setup") {
    sh "pip install --user --upgrade pip"
    withEnv(['PATH+LOCAL_JENKINS=/home/jenkins/.local/bin']) {
      sh "pip install --user markupsafe"
      sh "pip install --user jsonschema"
      sh "pip install --user couchdb"
      sh "pip install --user ansible==2.3.0.0"
      sh "pip install --user requests==2.10.0"
      sh "pip install --user docker==2.2.1"
      sh "pip install --user httplib2==0.9.2"
    }
    checkout([$class: 'GitSCM',
            branches: [[name: '*/master']],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CloneOption', noTags: true, reference: '', shallow: true]
            ],
            submoduleCfg: [],
            userRemoteConfigs: [[url: 'https://github.com/apache/incubator-openwhisk.git']]
        ])
  }

  stage("CouchDB Snapshot") {
    withEnv(['PATH+LOCAL_JENKINS=/home/jenkins/.local/bin']) {
      sh "python --version"
      sh "ansible --version"
      sh "ansible-playbook --version"
      dir('ansible') {
        def ANSIBLE_CMD = "ansible-playbook -i environments/local"
        sh "$ANSIBLE_CMD setup.yml"
        sh "$ANSIBLE_CMD couchdb.yml"
        sh "$ANSIBLE_CMD initdb.yml"
        sh "$ANSIBLE_CMD wipe.yml"
        sh "docker commit couchdb openwhisk/couchdb-snapshot"
        sh "docker images | grep openwhisk"
        sh "$ANSIBLE_CMD couchdb.yml -e mode=clean"
      }
    }
  }

  stage("Deploy DockerHub") {
    def JAVA_JDK_8=tool name: 'JDK 1.8 (latest)', type: 'hudson.model.JDK'
    withEnv(["Path+JDK=$JAVA_JDK_8/bin","JAVA_HOME=$JAVA_JDK_8"]) {
      withCredentials([usernamePassword(credentialsId: 'openwhisk_dockerhub', passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USER')]) {
          sh 'docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}'
      }
      def PUSH_CMD = "./gradlew distDocker -PdockerRegistry=docker.io -PdockerImagePrefix=openwhisk -x tests:dat:blackbox:badproxy:distDocker -x tests:dat:blackbox:badaction:distDocker -x sdk:docker:distDocker -x tools:cli:distDocker -x tools:cli:distDocker"
      def gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
      def shortCommit = gitCommit.take(7)
      sh "${PUSH_CMD} -PdockerImageTag=latest"
      sh "${PUSH_CMD} -PdockerImageTag=${shortCommit}"
      sh "docker tag openwhisk/couchdb-snapshot openwhisk/couchdb-snapshot:latest"
      sh "docker tag openwhisk/couchdb-snapshot openwhisk/couchdb-snapshot:${shortCommit}"
      sh "docker push openwhisk/couchdb-snapshot"

    }
  }

  stage("Clean") {
    sh "docker images"
    sh 'docker rmi -f $(docker images | grep openwhisk | awk \'{print $3}\') || true'
    sh "docker images"
  }

  stage("Notify") {
    withCredentials([string(credentialsId: 'openwhisk_slack_token', variable: 'OPENWHISK_SLACK_TOKEN')]) {
      sh "curl -X POST --data-urlencode 'payload={\"channel\": \"#dev\", \"username\": \"whiskbot\", \"text\": \"OpenWhisk Docker Images build and posted to https://hub.docker.com/u/openwhisk by Jenkins job ${BUILD_URL}\", \"icon_emoji\": \":openwhisk:\"}' https://hooks.slack.com/services/${OPENWHISK_SLACK_TOKEN}"
    }
    
  }
}
