FROM amazoncorretto:8u232
ARG OPENWHISK_JAR
ENV DOCKER_VERSION=18.06.3-ce
ENV WSK_VERSION=1.0.0
RUN yum -y update && yum -y install git tar curl hostname
RUN curl -sL \
  https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz \
  | tar xzvf -  -C /usr/bin --strip 1 docker/docker
RUN curl -sL \
  https://github.com/apache/openwhisk-cli/releases/download/${WSK_VERSION}/OpenWhisk_CLI-${WSK_VERSION}-linux-amd64.tgz \
  | tar xzvf - -C /usr/bin wsk
ADD ${OPENWHISK_JAR} /openwhisk-standalone.jar
ADD init.sh /
RUN touch /bin/xdg-open ; chmod +x /init.sh /bin/xdg-open
WORKDIR /
EXPOSE 8080
ENTRYPOINT  ["/init.sh"]
