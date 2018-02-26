FROM scala

ENV DOCKER_VERSION 1.12.0
ENV KUBERNETES_VERSION 1.6.4

RUN apk add --update openssl

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker && \
chmod +x /usr/bin/docker-runc

# Install kubernetes client
RUN wget --no-verbose https://storage.googleapis.com/kubernetes-release/release/v${KUBERNETES_VERSION}/bin/linux/amd64/kubectl && \
chmod +x kubectl && \
mv kubectl /usr/bin/kubectl

COPY build/distributions/invoker.tar ./
RUN tar xf invoker.tar && \
rm -f invoker.tar

COPY init.sh /
RUN chmod +x init.sh

EXPOSE 8080
CMD ["init.sh", "0"]
