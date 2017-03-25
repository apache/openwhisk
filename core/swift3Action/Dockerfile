# Dockerfile for swift actions, overrides and extends ActionRunner from actionProxy
# This Dockerfile is partially based on: https://github.com/swiftdocker/docker-swift/
FROM buildpack-deps:trusty

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install basic Python dependencies
RUN apt-get -y purge \
 && apt-get -y update \
 && apt-get -y install --fix-missing python2.7 python-gevent python-flask \
\
# Upgrade and install Swift dependencies
 && apt-get -y install --fix-missing build-essential curl wget libicu-dev \
\
# Install zip for compiling Swift actions
 && apt-get -y install zip \
\
# Clean up
 && apt-get clean

# Install clang manually, since SPM wants at least Clang 3-6
RUN cd / &&\
(curl -L -k http://llvm.org/releases/3.6.2/clang+llvm-3.6.2-x86_64-linux-gnu-ubuntu-14.04.tar.xz | tar xJ) &&\
cp -r /clang+llvm-3.6.2-x86_64-linux-gnu-ubuntu-14.04/* /usr/ &&\
rm -rf /clang+llvm-3.6.2-x86_64-linux-gnu-ubuntu-14.04

RUN update-alternatives --install /usr/bin/g++ g++ /usr/bin/clang++ 20
RUN update-alternatives --install /usr/bin/gcc gcc /usr/bin/clang 20

# Install Swift keys
RUN wget --no-verbose -O - https://swift.org/keys/all-keys.asc | gpg --import - && \
    gpg --keyserver hkp://pool.sks-keyservers.net --refresh-keys Swift

# Install Swift Ubuntu 14.04 Snapshot
#https://swift.org/builds/swift-3.0.1-release/ubuntu1404/swift-3.0.1-RELEASE/swift-3.0.1-RELEASE-ubuntu14.04.tar.gz

ENV SWIFT_VERSION 3.0.2
ENV SWIFT_RELEASE_TYPE RELEASE
ENV SWIFT_PLATFORM ubuntu14.04

RUN SWIFT_ARCHIVE_NAME=swift-$SWIFT_VERSION-$SWIFT_RELEASE_TYPE-$SWIFT_PLATFORM && \
    SWIFT_URL=https://swift.org/builds/swift-$SWIFT_VERSION-$(echo "$SWIFT_RELEASE_TYPE" | tr '[:upper:]' '[:lower:]')/$(echo "$SWIFT_PLATFORM" | tr -d .)/swift-$SWIFT_VERSION-$SWIFT_RELEASE_TYPE/$SWIFT_ARCHIVE_NAME.tar.gz && \
    echo $SWIFT_URL && \
    wget --no-verbose $SWIFT_URL && \
    wget --no-verbose $SWIFT_URL.sig && \
    gpg --verify $SWIFT_ARCHIVE_NAME.tar.gz.sig && \
    tar -xzf $SWIFT_ARCHIVE_NAME.tar.gz --directory / --strip-components=1 && \
    rm -rf $SWIFT_ARCHIVE_NAME* /tmp/* /var/tmp/*

# Add the action proxy
RUN mkdir -p /actionProxy
ADD actionproxy.py /actionProxy

# Add files needed to build and run action
RUN mkdir -p /swift3Action
ADD epilogue.swift /swift3Action
ADD buildandrecord.py /swift3Action
ADD swift3runner.py /swift3Action
ADD spm-build /swift3Action/spm-build


# Build kitura net
RUN touch /swift3Action/spm-build/main.swift
RUN python /swift3Action/buildandrecord.py; rm /swift3Action/spm-build/.build/release/Action
#RUN cd /swift3Action/spm-build; swift build -c release; rm /swift3Action/spm-build/.build/release/Action
ENV FLASK_PROXY_PORT 8080

CMD ["/bin/bash", "-c", "cd /swift3Action && PYTHONIOENCODING='utf-8' python -u swift3runner.py"]
