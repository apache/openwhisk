FROM buildpack-deps:trusty-curl

ENV DEBIAN_FRONTEND noninteractive

ENV VERSION 8
ENV UPDATE 77
ENV BUILD 03

ENV JAVA_HOME /usr/lib/jvm/java-${VERSION}-oracle
ENV JRE_HOME ${JAVA_HOME}/jre

RUN curl --silent --location --retry 3 --cacert /etc/ssl/certs/GeoTrust_Global_CA.pem \
  --header "Cookie: oraclelicense=accept-securebackup-cookie;" \
  http://download.oracle.com/otn-pub/java/jdk/"${VERSION}"u"${UPDATE}"-b"${BUILD}"/server-jre-"${VERSION}"u"${UPDATE}"-linux-x64.tar.gz \
  | tar xz -C /tmp && \
  mkdir -p /usr/lib/jvm && mv /tmp/jdk1.${VERSION}.0_${UPDATE} "${JAVA_HOME}" && \
  apt-get autoclean && apt-get --purge -y autoremove && \
  rm -rf /tmp/* /var/tmp/*

RUN update-alternatives --install "/usr/bin/java" "java" "${JRE_HOME}/bin/java" 1 && \
  update-alternatives --install "/usr/bin/javac" "javac" "${JAVA_HOME}/bin/javac" 1 && \
  update-alternatives --set java "${JRE_HOME}/bin/java" && \
  update-alternatives --set javac "${JAVA_HOME}/bin/javac"

ADD proxy /javaAction

RUN rm -rf /javaAction/.classpath /javaAction/.gitignore /javaAction/.gradle /javaAction/.project /javaAction/.settings /javaAction/Dockerfile /javaAction/build

RUN cd /javaAction; ./gradlew oneJar

RUN rm -rf /javaAction/src

CMD ["java", "-jar", "/javaAction/build/libs/javaAction-all.jar"]