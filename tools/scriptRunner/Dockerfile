# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM node:alpine

RUN apk --no-cache add \
  bash \
  git \
  libc6-compat \
  wget \
  zip

# Install `wsk` cli in /usr/local/bin
ENV WHISK_CLI_VERSION latest
RUN wget -q https://github.com/apache/incubator-openwhisk-cli/releases/download/$WHISK_CLI_VERSION/OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz && \
    tar xzf OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz -C /usr/local/bin wsk && \
    rm OpenWhisk_CLI-$WHISK_CLI_VERSION-linux-amd64.tgz

COPY init.sh /init.sh
RUN chmod +x /init.sh

CMD ["/init.sh"]
