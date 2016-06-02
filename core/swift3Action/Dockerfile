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

 # Kitura dependencies
RUN apt-get -y install autoconf libtool libkqueue-dev libkqueue0 libdispatch-dev libdispatch0 libcurl4-openssl-dev libbsd-dev
RUN apt-get clean

# Install Swift keys
RUN wget --no-verbose -O - https://swift.org/keys/all-keys.asc | gpg --import - && \
    gpg --keyserver hkp://pool.sks-keyservers.net --refresh-keys Swift

# Install Swift Ubuntu 14.04 Snapshot
ENV SWIFT_VERSION DEVELOPMENT-SNAPSHOT-2016-05-03-a
ENV SWIFT_PLATFORM ubuntu14.04

RUN SWIFT_ARCHIVE_NAME=swift-$SWIFT_VERSION-$SWIFT_PLATFORM && \
    SWIFT_URL=https://swift.org/builds/development/$(echo "$SWIFT_PLATFORM" | tr -d .)/swift-$SWIFT_VERSION/$SWIFT_ARCHIVE_NAME.tar.gz && \
    wget --no-verbose $SWIFT_URL && \
    wget --no-verbose $SWIFT_URL.sig && \
    gpg --verify $SWIFT_ARCHIVE_NAME.tar.gz.sig && \
    tar -xzf $SWIFT_ARCHIVE_NAME.tar.gz --directory / --strip-components=1 && \
    rm -rf $SWIFT_ARCHIVE_NAME* /tmp/* /var/tmp/*

#build lib dispatch for kitura net
RUN git clone -b experimental/foundation https://github.com/apple/swift-corelibs-libdispatch.git
RUN cd swift-corelibs-libdispatch && git submodule init && git submodule update && sh ./autogen.sh && ./configure --with-swift-toolchain=/usr --prefix=/usr && make && make install

# Copy the Flask proxy. Following the pattern in nodejsAction.
ADD . /swift3Action

RUN cd /swift3Action; rm -rf .project .settings build.xml Dockerfile

# Where the script will live.
#RUN mkdir -p /swift3Action
#RUN touch /swift3Action/action.swift

# Build kitura net
RUN mkdir -p /swift3Action
RUN touch /swift3Action/spm-build/main.swift
RUN cd /swift3Action/spm-build; swift build -Xcc -fblocks -Xlinker -rpath -Xlinker /swift3Action/spm-build/.build/debug
RUN rm /swift3Action/spm-build/.build/debug/Action

ENV FLASK_PROXY_PORT 8080

CMD ["/bin/bash", "-c", "cd swift3Action && python -u proxy.py"]
