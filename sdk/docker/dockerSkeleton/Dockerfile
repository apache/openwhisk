#
# Dockerfile for building a blackbox container with a nodejs server interfacing to a client binary.
#
FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

# Initial update and some basics.
# This odd double update seems necessary to get curl to download without 404 errors.
RUN apt-get update --fix-missing && \
apt-get install -y wget && \
apt-get update && \
apt-get install -y curl && \
apt-get update

# Get gcc
RUN apt-get update --fix-missing && \
apt-get -y install gcc

# install nodejs and npm
RUN curl -sL https://deb.nodesource.com/setup_0.12 | bash - && \
apt-get install -y nodejs

RUN mkdir /logs

ADD . /blackbox
RUN cd /blackbox/server; rm -rf build.xml Dockerfile README node_modules logs && ln -s /usr/lib/node_modules node_modules
RUN cd /blackbox/server; npm install . 

#
# User compilation step
#
RUN cd /blackbox/client; gcc -o clientApp example.c

# Final steps
RUN chmod uog+x /blackbox/client/clientApp
EXPOSE 8080
CMD ["/bin/bash", "-c", "cd blackbox/server && node ./app.js"]
