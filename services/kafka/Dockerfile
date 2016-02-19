# Builds Apache Kafka 0.9.0.0 from binary distribution.
# 
# Runs on Oracle Java 8 and a Ubuntu 14.04
#
FROM whisk/scala

# kludge for https://github.com/docker/docker/issues/14203
ENV DOCKER_FIX '                                             '

# primary, jmx, ras
EXPOSE 9092 7203 8080

# install nodejs and npm
# Install Kafka Stuff
RUN apt-get update -y && \
  curl -sL https://deb.nodesource.com/setup_0.12 | bash - && \
  apt-get install -y nodejs && \
  mkdir /kafka /data && \
  apt-get install -y ca-certificates
ENV KAFKA_RELEASE_ARCHIVE kafka_2.11-0.9.0.0.tgz

# Download Kafka binary distribution
#
# Install npm packages globally before doing ADDs which ruin docker cache
# Check artifact digest integrity
# Install Kafka to /kafka

WORKDIR /tmp
RUN wget http://www.us.apache.org/dist/kafka/0.9.0.0/${KAFKA_RELEASE_ARCHIVE} && \
    wget https://dist.apache.org/repos/dist/release/kafka/0.9.0.0/${KAFKA_RELEASE_ARCHIVE}.md5  && \
    npm install -g express@4.8.0 nano@5.10.0 process@0.10.1 websocket@1.0.17 && \
  echo VERIFY CHECKSUM: && \
  gpg --print-md MD5 ${KAFKA_RELEASE_ARCHIVE} 2>/dev/null && \
  cat ${KAFKA_RELEASE_ARCHIVE}.md5 && \
  tar -zx -C /kafka --strip-components=1 -f ${KAFKA_RELEASE_ARCHIVE} && \
  rm -rf kafka_*

#
# Install js app dependencies
ADD common   /common
ADD monitor  /monitor
RUN cd /monitor &&  ln -s /usr/lib/node_modules node_modules && npm install

ADD http://repo1.maven.org/maven2/org/slf4j/slf4j-log4j12/1.7.6/slf4j-log4j12-1.7.6.jar /kafka/libs/
ADD config /kafka/config
ADD start.sh /start.sh

# Set up a user to run Kafka
RUN chmod uog+rx /start.sh && \
  groupadd kafka && \
  useradd -d /kafka -g kafka -s /bin/false kafka && \
  chown -R kafka:kafka /kafka /data /logs /monitor /common
USER kafka
ENV PATH /kafka/bin:$PATH
WORKDIR /kafka

VOLUME [ "/data", "/logs" ]

CMD ["/start.sh"]
