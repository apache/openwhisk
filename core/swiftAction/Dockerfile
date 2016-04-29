# This Dockerfile is partially based on:
#   https://github.com/swiftdocker/docker-swift/
FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install Swift dependencies.
RUN apt-get -y update
RUN apt-get -y dist-upgrade
#RUN apt-get -y install  build-essential wget clang libedit-dev python2.7 python2.7-dev libicu52 rsync libxml2-dev
RUN apt-get -y install autoconf build-essential libtool wget clang libedit-dev python2.7 python2.7-dev libicu52 rsync libxml2-dev  libcurl3 libcurl4-openssl-dev libdispatch-dev libdispatch0 libblocksruntime-dev libkqueue-dev libpthread-workqueue-dev libbsd-dev libhttp-parser-dev libhiredis-dev libbsd-dev

RUN apt-get -y install git
RUN apt-get clean

# Install Python CGI helpers.
RUN apt-get -y install python-distribute python-pip
RUN pip install -U flask
RUN pip install -U gevent

# Install Swift keys
RUN wget -q -O - https://swift.org/keys/all-keys.asc | gpg --import - && \
    gpg --keyserver hkp://pool.sks-keyservers.net --refresh-keys Swift

# Install Swift Ubuntu 14.04 Snapshot
ENV SWIFT_VERSION DEVELOPMENT-SNAPSHOT-2016-04-25-a
ENV SWIFT_PLATFORM ubuntu14.04

RUN SWIFT_ARCHIVE_NAME=swift-$SWIFT_VERSION-$SWIFT_PLATFORM && \
    SWIFT_URL=https://swift.org/builds/development/$(echo "$SWIFT_PLATFORM" | tr -d .)/swift-$SWIFT_VERSION/$SWIFT_ARCHIVE_NAME.tar.gz && \
    wget -nv $SWIFT_URL && \
    wget -nv $SWIFT_URL.sig && \
    gpg --verify $SWIFT_ARCHIVE_NAME.tar.gz.sig && \
    tar -xzf $SWIFT_ARCHIVE_NAME.tar.gz --directory / --strip-components=1 && \
    rm -rf $SWIFT_ARCHIVE_NAME* /tmp/* /var/tmp/*

# Copy the Flask proxy. Following the pattern in nodejsAction.
ADD . /swiftAction

RUN cd /swiftAction; rm -rf .project .settings build.xml Dockerfile

# Where the script will live.
#RUN mkdir -p /swiftAction
#RUN touch /swiftAction/action.swift

#build lib dispatch for kitura net
RUN git clone -b experimental/foundation https://github.com/apple/swift-corelibs-libdispatch.git
RUN cd swift-corelibs-libdispatch && git submodule init && git submodule update && sh ./autogen.sh && ./configure --with-swift-toolchain=/usr --prefix=/usr && make && make install


# Build kitura net
RUN mkdir -p /swiftAction
RUN touch /swiftAction/spm-build/main.swift
RUN cd /swiftAction/spm-build; swift build -Xcc -fblocks

ENV FLASK_PROXY_PORT 8080

#CMD ["/bin/bash", "-c", "cd swiftAction && python proxy.py"]
