FROM scala

ENV DOCKER_VERSION 1.12.0

COPY build/distributions/invoker.tar ./
RUN apt-get update \
 && apt-get -y install --no-install-recommends docker.io \
 && apt-get clean && rm -rf /var/lib/apt/lists/* \
 && tar xf invoker.tar \
 && rm -f invoker.tar

EXPOSE 8080
