#
# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements.  See the NOTICE file distributed with this work for additional
# information regarding copyright ownership.  The ASF licenses this file to you
# under the Apache License, Version 2.0 (the # "License"); you may not use this
# file except in compliance with the License.  You may obtain a copy of the License
# at:
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations under the License.
#

FROM scala

ENV UID=1001 \
    NOT_ROOT_USER=owuser
ENV SWAGGER_UI_DOWNLOAD_SHA256=3d7ef5ddc59e10f132fe99771498f0f1ba7a2cbfb9585f9863d4191a574c96e7 \
    SWAGGER_UI_VERSION=3.6.0
    
ENV DOCKER_VERSION=1.12.0 \
    DOCKER_DOWNLOAD_SHA256=3dd07f65ea4a7b4c8829f311ab0213bca9ac551b5b24706f3e79a97e22097f8b

RUN apk add --update openssl

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
echo "${DOCKER_DOWNLOAD_SHA256}  docker-${DOCKER_VERSION}.tgz" | sha256sum -c - && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker && \
chmod +x /usr/bin/docker-runc

# Install swagger-ui
RUN curl -sSL -o swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz --no-verbose https://github.com/swagger-api/swagger-ui/archive/v${SWAGGER_UI_VERSION}.tar.gz && \
    echo "${SWAGGER_UI_DOWNLOAD_SHA256}  swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz" | sha256sum -c - && \
    mkdir swagger-ui && \
    tar zxf swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-${SWAGGER_UI_VERSION}/dist && \
    rm swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz && \
    sed -i s#http://petstore.swagger.io/v2/swagger.json#/api/v1/api-docs#g /swagger-ui/index.html

# Copy app jars
ADD build/distributions/lean.tar /

COPY init.sh /
RUN chmod +x init.sh
RUN adduser -D -u ${UID} -h /home/${NOT_ROOT_USER} -s /bin/bash ${NOT_ROOT_USER}

EXPOSE 8080
CMD ["./init.sh", "0"]
