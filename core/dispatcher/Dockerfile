FROM whisk/scala

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker 1.9.0
RUN wget --no-verbose -O /usr/bin/docker "https://get.docker.com/builds/Linux/x86_64/docker-1.9.0" && \
chmod +x /usr/bin/docker

COPY build/distributions/dispatcher.tar ./
RUN tar xf dispatcher.tar

EXPOSE 8080
