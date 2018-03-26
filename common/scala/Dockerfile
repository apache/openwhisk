FROM openjdk:8u151-jre-alpine3.7

RUN apk add --no-cache --update \
        # Curl is more familiar for downloads, for the moment...
        curl ca-certificates \
        # Bash is the scripting language of choice for the moment
        bash \
        # GNU sed is needed by transformEnvironment.sh
        sed \
    && update-ca-certificates

COPY transformEnvironment.sh copyJMXFiles.sh /
RUN mkdir /logs && chmod +x transformEnvironment.sh copyJMXFiles.sh
