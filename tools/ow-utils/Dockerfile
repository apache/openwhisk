# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM adoptopenjdk/openjdk8:jdk8u191-b12

ENV DOCKER_VERSION 1.12.0
ENV WHISK_CLI_VERSION latest
ENV WHISKDEPLOY_CLI_VERSION latest

RUN apt-get update && apt-get install -y \
  git \
  libffi-dev \
  nodejs \
  npm \
  python \
  python-pip \
  wget \
  zip \
&& rm -rf /var/lib/apt/lists/*

# Python packages
RUN pip install --upgrade pip
RUN pip install --upgrade setuptools
RUN pip install ansible==2.5.2
RUN pip install jinja2==2.9.6

# Install docker client
RUN wget --no-verbose https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
  tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
  rm -f docker-${DOCKER_VERSION}.tgz && \
  chmod +x /usr/bin/docker

# # Install `wsk` cli in /usr/local/bin
RUN wget -q https://github.com/apache/incubator-openwhisk-cli/releases/download/$WHISK_CLI_VERSION/OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz && \
  tar xzf OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz -C /usr/local/bin wsk && \
  rm OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz

COPY wskutil.py /bin
COPY wskprop.py /bin
COPY wskadmin /bin
