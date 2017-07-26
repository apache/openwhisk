FROM scala

# define kafka version and release version to download
ARG KAFKA_VERSION
ARG KAFKA_RELEASE

ENV DEBIAN_FRONTEND noninteractive

# Install swagger-ui
RUN wget --no-verbose https://github.com/swagger-api/swagger-ui/archive/v2.1.4.tar.gz && \
    mkdir swagger-ui && \
    tar zxf v2.1.4.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-2.1.4/dist && \
    rm v2.1.4.tar.gz && \
    perl -pi -w -e  's{http://petstore.swagger.io/v2/swagger.json}{/api/v1/api-docs}g;' /swagger-ui/index.html

#
# Copy app jars
COPY build/distributions/controller.tar ./
RUN tar xf controller.tar

EXPOSE 8080

# Download Kafka
RUN wget --no-verbose -P /tmp http://www.us.apache.org/dist/kafka/${KAFKA_VERSION}/${KAFKA_RELEASE}

# untar Kafka to /kafka
RUN mkdir /kafka
RUN tar -xf /tmp/${KAFKA_RELEASE} -C /kafka --strip=1

# remove Kafka tarball
RUN rm /tmp/${KAFKA_RELEASE}

# Copy the start script
COPY controller.sh /controller.sh
