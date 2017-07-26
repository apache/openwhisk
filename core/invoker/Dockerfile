FROM scala

ENV DOCKER_VERSION 1.12.0

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN wget --no-verbose https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker && \
chmod +x /usr/bin/docker-runc

# Copy the Invoker built from gradle
COPY build/distributions/invoker.tar ./
RUN tar xf invoker.tar && \
rm -f invoker.tar

EXPOSE 8080

# Download Kafka
ENV KAFKA_VERSION=0.10.0.1
ENV KAFKA_RELEASE=kafka_2.11-0.10.0.1.tgz
RUN wget --no-verbose -P /tmp http://www.us.apache.org/dist/kafka/${KAFKA_VERSION}/${KAFKA_RELEASE}

# untar Kafka to /kafka
RUN mkdir /kafka
RUN tar -xf /tmp/${KAFKA_RELEASE} -C /kafka --strip=1

# remove Kafka tarball
RUN rm /tmp/${KAFKA_RELEASE}

# Copy the init script
COPY invoker.sh /invoker.sh
