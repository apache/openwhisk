FROM buildpack-deps:trusty-curl

ENV DEBIAN_FRONTEND noninteractive

# Initial update and some basics.
RUN apt-get update && apt-get install -y --no-install-recommends \
    imagemagick \
    unzip \
    zip \
    make \
    g++ \
    python \
    jq \
    git \
    && rm -rf /var/lib/apt/lists/*

ADD . /nodejsAction
