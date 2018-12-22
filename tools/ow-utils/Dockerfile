# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

ARG ADOPTOPENJDK_TARGET_REGISTRY=adoptopenjdk
FROM ${ADOPTOPENJDK_TARGET_REGISTRY}/openjdk8:jdk8u192-b12

ENV DOCKER_VERSION 1.12.0
ENV WHISK_CLI_VERSION latest
ENV WHISKDEPLOY_CLI_VERSION latest

RUN apt-get -q update \
  && DEBIAN_FRONTEND=noninteractive apt-get -q install -y --no-install-recommends \
    apt-transport-https \
    gcc \
    git \
    gnupg \
    libc6-dev \
    libffi-dev \
    libpython2.7-dev \
    make \
    nodejs \
    npm \
    python \
    python-pip \
    software-properties-common \
    wget \
    zip \
  && rm -rf /var/lib/apt/lists/*

# Make sure pipes (even implicit ones) fail the job if they fail
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# Upgrade to a modern pip
RUN pip install --upgrade pip==18.1

# Python packages
RUN pip install --upgrade setuptools==40.6.2 \
 && pip install ansible==2.5.2 \
 && pip install jinja2==2.9.6

# Install docker client
RUN if [ "$(uname -m)" = x86_64 ]; then \
      wget -q -O - \
        https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz \
      | tar --strip-components 1 -xvzf - -C /usr/bin docker/docker && chmod +x /usr/bin/docker \
; else \
     debarch="$(uname -m | sed -e 's/x86_64/amd64/' -e 's/ppc64le/ppc64el/' -e 's/aarch64/arm64/')" \
     && wget -q -O - https://download.docker.com/linux/ubuntu/gpg | apt-key add - \
     && add-apt-repository \
        "deb [arch=$debarch] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
     && apt-get update \
     && apt-get install -y --no-install-recommends docker-ce \
     && rm -rf /var/lib/apt/lists \
; fi

# # Install `wsk` cli in /usr/local/bin
RUN wskarch="$(uname -m | sed -e 's/x86_64/amd64/' -e 's/aarch64/arm64/')" \
 && wget -q -O - \
    "https://github.com/apache/incubator-openwhisk-cli/releases/download/$WHISK_CLI_VERSION/OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-${wskarch}.tgz" \
  | tar xzf - -C /usr/local/bin wsk

# # Install `wskdeploy` cli in /usr/loca
RUN wskarch="$(uname -m | sed -e 's/x86_64/amd64/' -e 's/aarch64/arm64/')" \
 && wget -q -O - \
    "https://github.com/apache/incubator-openwhisk-wskdeploy/releases/download/$WHISKDEPLOY_CLI_VERSION/openwhisk_wskdeploy-$WHISKDEPLOY_CLI_VERSION-linux-${wskarch}.tgz" \
 | tar xzf - -C /usr/local/bin wskdeploy
