# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM alpine:3.6

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

ENV VERSION 8
ENV UPDATE 141
ENV BUILD 15
ENV SIG 336fa29ff2bb4ef291e347e091f7f4a7
ENV GLIBC_VERSION 2.26-r0

ENV JAVA_HOME /usr/lib/jvm/java-${VERSION}-oracle
ENV JRE_HOME ${JAVA_HOME}/jre
ENV PATH $JAVA_HOME/bin:$PATH


RUN apk upgrade --update && \
    apk add --update libstdc++ curl ca-certificates bash sed wget && \
    update-ca-certificates && \
    for pkg in glibc-${GLIBC_VERSION} glibc-bin-${GLIBC_VERSION} glibc-i18n-${GLIBC_VERSION}; do curl -sSL https://github.com/andyshinn/alpine-pkg-glibc/releases/download/${GLIBC_VERSION}/${pkg}.apk -o /tmp/${pkg}.apk; done && \
    apk add --allow-untrusted /tmp/*.apk && \
    rm -v /tmp/*.apk && \
    ( /usr/glibc-compat/bin/localedef --force --inputfile POSIX --charmap UTF-8 C.UTF-8 || true ) && \
    echo "export LANG=${LANG}" > /etc/profile.d/locale.sh && \
    /usr/glibc-compat/sbin/ldconfig /lib /usr/glibc-compat/lib && \
    curl --silent --location --retry 3 --header "Cookie: oraclelicense=accept-securebackup-cookie" \
    http://download.oracle.com/otn-pub/java/jdk/"${VERSION}"u"${UPDATE}"-b"${BUILD}"/"${SIG}"/jdk-"${VERSION}"u"${UPDATE}"-linux-x64.tar.gz \
    | tar xz -C /tmp && \
    mkdir -p /usr/lib/jvm && mv /tmp/jdk1.${VERSION}.0_${UPDATE} "${JAVA_HOME}" && \
    rm -rf "$JAVA_HOME"/*src.zip \
           "$JAVA_HOME"/lib/missioncontrol \
           "$JAVA_HOME"/lib/visualvm \
           "$JAVA_HOME"/lib/*javafx* \
           "$JAVA_HOME"/jre/plugin \
           "$JAVA_HOME"/jre/bin/javaws \
           "$JAVA_HOME"/jre/bin/jjs \
           "$JAVA_HOME"/jre/bin/orbd \
           "$JAVA_HOME"/jre/bin/pack200 \
           "$JAVA_HOME"/jre/bin/policytool \
           "$JAVA_HOME"/jre/bin/rmid \
           "$JAVA_HOME"/jre/bin/rmiregistry \
           "$JAVA_HOME"/jre/bin/servertool \
           "$JAVA_HOME"/jre/bin/tnameserv \
           "$JAVA_HOME"/jre/bin/unpack200 \
           "$JAVA_HOME"/jre/lib/javaws.jar \
           "$JAVA_HOME"/jre/lib/deploy* \
           "$JAVA_HOME"/jre/lib/desktop \
           "$JAVA_HOME"/jre/lib/*javafx* \
           "$JAVA_HOME"/jre/lib/*jfx* \
           "$JAVA_HOME"/jre/lib/amd64/libdecora_sse.so \
           "$JAVA_HOME"/jre/lib/amd64/libprism_*.so \
           "$JAVA_HOME"/jre/lib/amd64/libfxplugins.so \
           "$JAVA_HOME"/jre/lib/amd64/libglass.so \
           "$JAVA_HOME"/jre/lib/amd64/libgstreamer-lite.so \
           "$JAVA_HOME"/jre/lib/amd64/libjavafx*.so \
           "$JAVA_HOME"/jre/lib/amd64/libjfx*.so \
           "$JAVA_HOME"/jre/lib/ext/jfxrt.jar \
           "$JAVA_HOME"/jre/lib/ext/nashorn.jar \
           "$JAVA_HOME"/jre/lib/oblique-fonts \
           "$JAVA_HOME"/jre/lib/plugin.jar \
           /tmp/* /var/cache/apk/* && \
    echo 'hosts: files mdns4_minimal [NOTFOUND=return] dns mdns4' >> /etc/nsswitch.conf && \
    # https://github.com/anapsix/docker-alpine-java/issues/18#issue-167437838
    # If you want to change the default values here, also change the values in 042-set-jvm-ttl.sh
    sed -i s/#networkaddress.cache.ttl=-1/networkaddress.cache.ttl=10/ ${JAVA_HOME}/jre/lib/security/java.security && \
    sed -i s/networkaddress.cache.negative.ttl=10/networkaddress.cache.negative.ttl=0/ ${JAVA_HOME}/jre/lib/security/java.security




RUN mkdir /logs

COPY transformEnvironment.sh /
RUN chmod +x transformEnvironment.sh

COPY copyJMXFiles.sh /
RUN chmod +x copyJMXFiles.sh
