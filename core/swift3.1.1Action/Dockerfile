# Dockerfile for swift actions, overrides and extends ActionRunner from actionProxy
# This Dockerfile is partially based on: https://github.com/IBM-Swift/swift-ubuntu-docker/blob/master/swift-development/Dockerfile
FROM ibmcom/swift-ubuntu:3.1.1

ENV SWIFT_LOCATION /root/swift-3.1.1-RELEASE-ubuntu14.04

# Set WORKDIR
WORKDIR /

# Upgrade and install basic Python dependencies
RUN apt-get -y update \
 && apt-get -y install --fix-missing python2.7 python-gevent python-flask zip

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
#RUN cd /swift3Action/spm-build; swift build -v -c release; rm /swift3Action/spm-build/.build/release/Action
ENV FLASK_PROXY_PORT 8080

CMD ["/bin/bash", "-c", "cd /swift3Action && PYTHONIOENCODING='utf-8' python -u swift3runner.py"]