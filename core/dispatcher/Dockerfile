FROM whisk/scala

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker 1.9.0
RUN wget -O /usr/bin/docker "https://get.docker.com/builds/Linux/x86_64/docker-1.9.0" && \
chmod +x /usr/bin/docker

EXPOSE 8080

# Copy app jars
COPY dispatcher.jar dispatcher-libs.tar startDispatcher.sh ./
RUN tar xf dispatcher-libs.tar && rm dispatcher-libs.tar && \
chmod +x startDispatcher.sh

