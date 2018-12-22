# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

ARG ADOPTOPENJDK_TARGET_REGISTRY=adoptopenjdk
FROM ${ADOPTOPENJDK_TARGET_REGISTRY}/openjdk8:jdk8u192-b12-alpine

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

RUN apk add --no-cache --update sed curl bash

RUN mkdir /logs

COPY transformEnvironment.sh /
RUN chmod +x transformEnvironment.sh

COPY copyJMXFiles.sh /
RUN chmod +x copyJMXFiles.sh
