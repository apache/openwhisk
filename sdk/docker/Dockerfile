#
# Dockerfile for building a blackbox container with a nodejs server interfacing to a client binary.
#
FROM buildpack-deps:trusty-curl

ENV DEBIAN_FRONTEND noninteractive

# based on https://github.com/nodejs/docker-node
ENV NODE_VERSION 0.12.14
RUN curl -SLO "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
  && tar -xzf "node-v$NODE_VERSION-linux-x64.tar.gz" -C /usr/local --strip-components=1 \
  && rm "node-v$NODE_VERSION-linux-x64.tar.gz"

# Get gcc
RUN apt-get update --fix-missing && \
apt-get -y install gcc

RUN mkdir /logs

ADD ./client /blackbox/client
ADD ./server /blackbox/server

RUN cd /blackbox/server; rm -rf node_modules logs
RUN cd /blackbox/server; npm install . 

#
# User compilation step
#
RUN cd /blackbox/client; gcc -o action example.c

# Final steps
RUN chmod uog+x /blackbox/client/action
EXPOSE 8080
CMD ["/bin/bash", "-c", "cd blackbox/server && node ./app.js"]
