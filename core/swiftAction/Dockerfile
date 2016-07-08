# This Dockerfile is partially based on:
#   https://github.com/swiftdocker/docker-swift/
FROM buildpack-deps:trusty

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install Python dependencies
RUN apt-get -y purge && \
    apt-get -y update && \
    apt-get -y install --fix-missing python2.7 python-distribute python-pip

# Install Python proxy support
RUN apt-get -y install --fix-missing python2.7-dev python-gevent python-flask

# Upgrade and install Swift dependencies
RUN apt-get -y install build-essential wget clang libedit-dev libicu52 libxml2-dev
RUN apt-get clean

# Install Swift keys
RUN wget --no-verbose -O - https://swift.org/keys/all-keys.asc | gpg --import - && \
    gpg --keyserver hkp://pool.sks-keyservers.net --refresh-keys Swift

# Install Swift Ubuntu 14.04 Snapshot
ENV SWIFT_VERSION 2.2-SNAPSHOT-2016-01-11-a
ENV SWIFT_PLATFORM ubuntu14.04

RUN SWIFT_ARCHIVE_NAME=swift-$SWIFT_VERSION-$SWIFT_PLATFORM && \
    SWIFT_URL=https://swift.org/builds/$(echo "$SWIFT_PLATFORM" | tr -d .)/swift-$SWIFT_VERSION/$SWIFT_ARCHIVE_NAME.tar.gz && \
    wget --no-verbose $SWIFT_URL && \
    wget --no-verbose $SWIFT_URL.sig && \
    gpg --verify $SWIFT_ARCHIVE_NAME.tar.gz.sig && \
    tar -xzf $SWIFT_ARCHIVE_NAME.tar.gz --directory / --strip-components=1 && \
    rm -rf $SWIFT_ARCHIVE_NAME* /tmp/* /var/tmp/*

# Copy the Flask proxy. Following the pattern in nodejsAction.
ADD . /swiftAction

RUN cd /swiftAction; rm -rf .project .settings build.xml Dockerfile

# Where the script will live.
RUN mkdir -p /swiftAction
RUN touch /swiftAction/action.swift

ENV FLASK_PROXY_PORT 8080

CMD ["/bin/bash", "-c", "cd swiftAction && python -u proxy.py"]
