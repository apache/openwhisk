#!groovy
node('xenial&&!H21&&!H22&&!H11&&!ubuntu-eu3') {
  sh "env"
  sh "docker version"
  sh "docker info"

  checkout scm

  stage("Build and Deploy to DockerHub") {
    def JAVA_JDK_8=tool name: 'JDK 1.8 (latest)', type: 'hudson.model.JDK'
    withEnv(["Path+JDK=$JAVA_JDK_8/bin","JAVA_HOME=$JAVA_JDK_8"]) {
      withCredentials([usernamePassword(credentialsId: 'openwhisk_dockerhub', passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USER')]) {
          sh 'docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}'
      }
      def PUSH_CMD = "./gradlew :core:controller:distDocker :core:invoker:distDocker -PdockerRegistry=docker.io -PdockerImagePrefix=openwhisk"
      def gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
      def shortCommit = gitCommit.take(7)
      sh "${PUSH_CMD} -PdockerImageTag=latest"
      sh "${PUSH_CMD} -PdockerImageTag=${shortCommit}"
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
