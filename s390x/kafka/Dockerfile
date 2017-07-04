# Builds an image for Apache Kafka from binary distribution.
#
# TODO:  This note is no longer accurate; we are using OpenJDK.  We may
#        choose to use the IBM Java Distro -- one more TBD.
#
# The netflixoss/java base image runs IBM Java 8 installed atop the
# ubuntu:trusty (14.04) official image.  IBM Java 8 currently does not
# execute (or fail) cleanly with Alpine.

#LABEL kafka

FROM s390x/ibmjava:sfj

# The Scala 2.12 build is currently recommended by the project.
ENV KAFKA_VERSION=0.11.0.0 KAFKA_SCALA_VERSION=2.11 JMX_PORT=7203
ENV KAFKA_RELEASE_ARCHIVE kafka_${KAFKA_SCALA_VERSION}-${KAFKA_VERSION}.tgz

RUN mkdir -p /kafka /data /logs

# RUN apt-get update \
#   && DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates \
#   && apt-get clean \
#   && rm -rf /var/lib/apt/lists/*

# Download Kafka binary distribution
#ADD http://www.us.apache.org/dist/kafka/${KAFKA_VERSION}/${KAFKA_RELEASE_ARCHIVE} /tmp/
#ADD https://dist.apache.org/repos/dist/release/kafka/${KAFKA_VERSION}/${KAFKA_RELEASE_ARCHIVE}.md5 /tmp/

WORKDIR /tmp

# Check artifact digest integrity
RUN  wget -q http://www.us.apache.org/dist/kafka/${KAFKA_VERSION}/${KAFKA_RELEASE_ARCHIVE} \
  && wget -q https://dist.apache.org/repos/dist/release/kafka/${KAFKA_VERSION}/${KAFKA_RELEASE_ARCHIVE}.md5 \
  && echo VERIFY CHECKSUM: \
  && gpg --print-md MD5 ${KAFKA_RELEASE_ARCHIVE} 2>/dev/null \
  && cat ${KAFKA_RELEASE_ARCHIVE}.md5 \
  && tar -zx -C /kafka --strip-components=1 -f ${KAFKA_RELEASE_ARCHIVE} \
  && rm -rf /tmp/kafka_*.tgz* \
  && sed -i.bak -e 's/-Xloggc:/-Xverbosegclog:/g' /kafka/bin/kafka-run-class.sh

COPY config /kafka/config
COPY start.sh /start.sh

# Set up a user to run Kafka
RUN groupadd kafka && \
  useradd -d /kafka -g kafka -s /bin/false kafka && \
  chown -R kafka:kafka /kafka /data /logs
USER kafka
ENV PATH /kafka/bin:$PATH
WORKDIR /kafka

# broker, jmx
EXPOSE 9092 ${JMX_PORT}
VOLUME [ "/data", "/logs" ]

CMD ["/start.sh"]
