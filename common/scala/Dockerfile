# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM openjdk:8u151-jdk-alpine
RUN apk add --update sed curl bash

RUN mkdir /logs

COPY transformEnvironment.sh /
RUN chmod +x transformEnvironment.sh

COPY copyJMXFiles.sh /
RUN chmod +x copyJMXFiles.sh
