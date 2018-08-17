# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM scala

ENV DOCKER_VERSION 1.12.0

RUN apk add --update openssl

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker && \
chmod +x /usr/bin/docker-runc

ADD build/distributions/invoker.tar ./

COPY init.sh /
RUN chmod +x init.sh

EXPOSE 8080
CMD ["./init.sh", "0"]
