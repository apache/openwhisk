# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM adoptopenjdk/openjdk8:x86_64-alpine-jdk8u172-b11

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

ENV UID=1001 \
    NOT_ROOT_USER=owuser

RUN apk add --update sed bash

# Copy app jars
ADD build/distributions/user-metrics.tar /

COPY transformEnvironment.sh /
COPY init.sh /
RUN chmod +x init.sh && chmod +x transformEnvironment.sh

RUN adduser -D -u ${UID} -h /home/${NOT_ROOT_USER} -s /bin/bash ${NOT_ROOT_USER}
USER ${NOT_ROOT_USER}

# Prometheus port
EXPOSE 9095
CMD ["./init.sh", "0"]
