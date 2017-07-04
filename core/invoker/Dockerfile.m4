FROM scala

ENV DOCKER_VERSION 1.12.0

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
m4_ifdef(`S390X',`
RUN apt-get update && apt-get -y install docker.io \
 && apt-get clean && rm -rf /var/lib/apt/lists/*
',`
RUN wget --no-verbose https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker && \
chmod +x /usr/bin/docker-runc
<<<<<<< HEAD:core/invoker/Dockerfile.m4
')

COPY build/distributions/invoker.tar ./
RUN tar xf invoker.tar && \
rm -f invoker.tar

EXPOSE 8080
