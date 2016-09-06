# Dockerfile for swift3 actions, overrides and extends ActionRunner from actionProxy
# This Dockerfile is partially based on: https://github.com/swiftdocker/docker-swift/
FROM buildpack-deps:trusty

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install basic Python dependencies
RUN apt-get -y purge \
 && apt-get -y update \
 && apt-get -y install --fix-missing python2.7 python-gevent python-flask \
\
# Upgrade and install Swift dependencies
 && apt-get -y install --fix-missing build-essential wget clang libedit-dev libicu52 libxml2-dev \
\
# Clean up
 && apt-get clean

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

# Add the action proxy
RUN mkdir -p /actionProxy
ADD actionproxy.py /actionProxy

# Add swift action runner
RUN mkdir -p /swiftAction
RUN touch /swiftAction/action.swift
ADD epilogue.swift /swiftAction
ADD swiftrunner.py /swiftAction

ENV FLASK_PROXY_PORT 8080

CMD ["/bin/bash", "-c", "cd swiftAction && python -u swiftrunner.py"]
