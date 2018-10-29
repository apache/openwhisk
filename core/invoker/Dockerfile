# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM scala

ENV UID=1001 \
    NOT_ROOT_USER=owuser
ENV DOCKER_VERSION=18.06.1-ce \
    DOCKER_DOWNLOAD_SHA256=83be159cf0657df9e1a1a4a127d181725a982714a983b2bdcc0621244df93687

RUN apk add --update openssl

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz && \
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
echo "${DOCKER_DOWNLOAD_SHA256}  docker-${DOCKER_VERSION}.tgz" | sha256sum -c - && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker && \
chmod +x /usr/bin/docker-runc

ADD build/distributions/invoker.tar ./

COPY init.sh /
RUN chmod +x init.sh
RUN adduser -D -u ${UID} -h /home/${NOT_ROOT_USER} -s /bin/bash ${NOT_ROOT_USER}

EXPOSE 8080
CMD ["./init.sh", "0"]
