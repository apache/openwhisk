# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

from debian:jessie-slim

ENV DOCKER_VERSION 1.12.0

RUN apt-get update && apt-get install -y \
  curl \
  git \
  libffi-dev \
  python \
  python-pip \
  wget \
  zip

# Install docker client
RUN wget --no-verbose https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker

RUN pip install --upgrade pip
RUN pip install --upgrade setuptools
RUN pip install ansible==2.5.2
RUN pip install jinja2==2.9.6

CMD ["/usr/local/bin/ansible-playbook", "/task/playbook.yml"]
