# Builds Apache Kafka 0.9.0.0 from binary distribution.
# 
# Runs on Oracle Java 8 and a Ubuntu 14.04
#
FROM scala

# kludge for https://github.com/docker/docker/issues/14203
ENV DOCKER_FIX '                                             '

# primary, jmx, ras
EXPOSE 9092 7203

# Install Kafka Stuff
RUN mkdir /kafka /data
ENV KAFKA_RELEASE 0.10.0.0
ENV KAFKA_SCALA_VERSION 2.11
ENV KAFKA_RELEASE_ARCHIVE kafka_${KAFKA_SCALA_VERSION}-${KAFKA_RELEASE}.tgz

# Download Kafka binary distribution
#
# Install npm packages globally before doing ADDs which ruin docker cache
# Check artifact digest integrity
# Install Kafka to /kafka

WORKDIR /tmp
RUN wget --no-verbose http://www.us.apache.org/dist/kafka/${KAFKA_RELEASE}/${KAFKA_RELEASE_ARCHIVE} && \
    wget --no-verbose https://dist.apache.org/repos/dist/release/kafka/${KAFKA_RELEASE}/${KAFKA_RELEASE_ARCHIVE}.md5  && \
  echo VERIFY CHECKSUM: && \
  gpg --print-md MD5 ${KAFKA_RELEASE_ARCHIVE} 2>/dev/null && \
  cat ${KAFKA_RELEASE_ARCHIVE}.md5 && \
  tar -zx -C /kafka --strip-components=1 -f ${KAFKA_RELEASE_ARCHIVE} && \
  rm -rf kafka_*

ADD config /kafka/config
ADD start.sh /start.sh

# Set up a user to run Kafka
RUN chmod uog+rx /start.sh && \
  groupadd kafka && \
  useradd -d /kafka -g kafka -s /bin/false kafka && \
  chown -R kafka:kafka /kafka /data /logs
USER kafka
ENV PATH /kafka/bin:$PATH
WORKDIR /kafka

VOLUME [ "/data", "/logs" ]

CMD ["/start.sh"]
