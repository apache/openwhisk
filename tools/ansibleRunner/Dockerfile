# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

from alpine

ENV DOCKER_VERSION 1.12.0

RUN apk --no-cache add \
  bash \
  curl \
  git \
  libc6-compat \
  libffi \
  openssl \
  python \
  py-pip \
  wget \
  zip

RUN apk --no-cache add --virtual build-dependencies \
  python-dev \
  build-base \
  libffi-dev \
  openssl-dev

# Install docker client
RUN wget --no-verbose https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker

RUN pip install --upgrade pip
RUN pip install --upgrade setuptools
RUN pip install ansible==2.5.2
RUN pip install jinja2==2.9.6

RUN apk del build-dependencies

CMD ["/usr/bin/ansible-playbook", "/task/playbook.yml"]
