# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM openjdk:8u151-jdk-alpine
#FROM adoptopenjdk/openjdk8-openj9:jdk8u162-b12_openj9-0.8.0-alpine
RUN apk add --update curl

#ENV JAVA_HOME=/opt/jdk
#ENV JAVA_HOME=/opt/java/openjdk/jdk8u162-b12_openj9-0.8.0
#RUN sed -i s/#networkaddress.cache.ttl=-1/networkaddress.cache.ttl=10/ ${JAVA_HOME}/jre/lib/security/java.security
#RUN sed -i s/networkaddress.cache.negative.ttl=10/networkaddress.cache.negative.ttl=0/ ${JAVA_HOME}/jre/lib/security/java.security

RUN mkdir /logs

COPY transformEnvironment.sh /
RUN chmod +x transformEnvironment.sh

COPY copyJMXFiles.sh /
RUN chmod +x copyJMXFiles.sh
