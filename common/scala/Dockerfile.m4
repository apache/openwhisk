FROM m4_ifdef(`S390X',`s390x/buildpack-deps:xenial-curl',`buildpack-deps:trusty-curl')

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update \
 && apt-get -y install locales \
 && locale-gen en_US.UTF-8 \
 && apt-get clean && rm -rf /var/lib/apt/lists/
 
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

m4_ifdef(`S390X',`
#----------------------- IBM Java Installation for System Z architecture ---------------------------
ENV JAVA_HOME /opt/ibm/java-s390x-80
ENV JRE_HOME ${JAVA_HOME}/jre

#COPY installer.properties .

RUN curl -sSL -O http://public.dhe.ibm.com/ibmdl/export/pub/systems/cloud/runtimes/java/8.0.4.6/linux/s390x/ibm-java-s390x-sdk-8.0-4.6.bin \
 && echo "INSTALLER_UI=silent" > ./installer.properties \
 && echo "LICENSE_ACCEPTED=TRUE" >> ./installer.properties \
 && sh ./ibm-java-s390x-sdk-8.0-4.6.bin -i silent \
 && rm ./ibm-java-s390x-sdk-8.0-4.6.bin ./installer.properties
',`
#---------------------- Oracle Java Installation from here Onward -----------------------------------
ENV VERSION 8
ENV UPDATE 131
ENV BUILD 11

ENV JAVA_HOME /usr/lib/jvm/java-${VERSION}-oracle
ENV JRE_HOME ${JAVA_HOME}/jre

RUN curl --silent --location --retry 3 --cacert /etc/ssl/certs/GeoTrust_Global_CA.pem \
  --header "Cookie: oraclelicense=accept-securebackup-cookie;" \
  http://download.oracle.com/otn-pub/java/jdk/"${VERSION}"u"${UPDATE}"-b"${BUILD}"/d54c1d3a095b4ff2b6607d096fa80163/server-jre-"${VERSION}"u"${UPDATE}"-linux-x64.tar.gz \
  | tar xz -C /tmp && \
  mkdir -p /usr/lib/jvm && mv /tmp/jdk1.${VERSION}.0_${UPDATE} "${JAVA_HOME}" && \
  apt-get autoclean && apt-get --purge -y autoremove && \
  rm -rf /tmp/* /var/tmp/*
')

RUN update-alternatives --install "/usr/bin/java" "java" "${JRE_HOME}/bin/java" 1 && \
  update-alternatives --install "/usr/bin/javac" "javac" "${JAVA_HOME}/bin/javac" 1 && \
  update-alternatives --set java "${JRE_HOME}/bin/java" && \
  update-alternatives --set javac "${JAVA_HOME}/bin/javac" && \
  mkdir /logs
