# This Dockerfile is partially based on:
#   https://github.com/swiftdocker/docker-swift/
FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install Swift dependencies.
RUN apt-get -y purge
RUN apt-get -y update
RUN apt-get -y dist-upgrade
RUN apt-get -y install build-essential wget clang libedit-dev python2.7 python2.7-dev libicu52 rsync libxml2-dev
RUN apt-get -y install git-core
RUN apt-get clean

# Install Python CGI helpers.
RUN apt-get -y install python-distribute python-pip python-gevent
RUN pip install -U flask

# Install Swift keys
RUN wget -q -O - https://swift.org/keys/all-keys.asc | gpg --import - && \
    gpg --keyserver hkp://pool.sks-keyservers.net --refresh-keys Swift

# Install Swift Ubuntu 14.04 Snapshot
ENV SWIFT_VERSION 2.2-SNAPSHOT-2016-01-11-a
ENV SWIFT_PLATFORM ubuntu14.04

RUN SWIFT_ARCHIVE_NAME=swift-$SWIFT_VERSION-$SWIFT_PLATFORM && \
    SWIFT_URL=https://swift.org/builds/$(echo "$SWIFT_PLATFORM" | tr -d .)/swift-$SWIFT_VERSION/$SWIFT_ARCHIVE_NAME.tar.gz && \
    wget -nv $SWIFT_URL && \
    wget -nv $SWIFT_URL.sig && \
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

CMD ["/bin/bash", "-c", "cd swiftAction && python proxy.py"]
