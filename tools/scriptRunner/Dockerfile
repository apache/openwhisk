# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM node:slim

RUN apt-get update && apt-get install -y \
  git \
  wget \
  zip

# Install `wsk` cli in /usr/local/bin
ENV WHISK_CLI_VERSION latest
RUN wget -q https://github.com/apache/incubator-openwhisk-cli/releases/download/$WHISK_CLI_VERSION/OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz && \
    tar xzf OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz -C /usr/local/bin wsk && \
    rm OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz

# Install `wskdeploy` cli in /usr/local/bin
ENV WHISKDEPLOY_CLI_VERSION latest
RUN wget -q https://github.com/apache/incubator-openwhisk-wskdeploy/releases/download/$WHISKDEPLOY_CLI_VERSION/openwhisk_wskdeploy-$WHISKDEPLOY_CLI_VERSION-linux-amd64.tgz && \
    tar xzf openwhisk_wskdeploy-$WHISK_CLI_VERSION-linux-amd64.tgz -C /usr/local/bin wskdeploy && \
    rm openwhisk_wskdeploy-$WHISK_CLI_VERSION-linux-amd64.tgz

COPY init.sh /init.sh
RUN chmod +x /init.sh

CMD ["/init.sh"]
