# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM scala

ENV UID=1001 \
    NOT_ROOT_USER=owuser
    ENV DOCKER_VERSION=18.06.3-ce
# If you change the docker version here, it has implications on invoker runc support. 
# Docker server version and the invoker docker version must be the same to enable runc usage. 
# If this cannot be guaranteed, set `invoker_use_runc: false` in the ansible env. 


RUN apk add --update openssl

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz && \
    tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
    tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
    rm -f docker-${DOCKER_VERSION}.tgz && \
    chmod +x /usr/bin/docker && \
    chmod +x /usr/bin/docker-runc

ADD build/distributions/invoker.tar ./

COPY init.sh /
RUN chmod +x init.sh

# When running the invoker as a non-root user this has implications on the standard directory where runc stores its data.
# The non-root user should have access on the directory and corresponding permission to make changes on it. 
RUN adduser -D -u ${UID} -h /home/${NOT_ROOT_USER} -s /bin/bash ${NOT_ROOT_USER}

EXPOSE 8080
CMD ["./init.sh", "0"]
