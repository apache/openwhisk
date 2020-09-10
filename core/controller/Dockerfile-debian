#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

FROM scala

ENV UID=1001 \
    NOT_ROOT_USER=owuser
ENV SWAGGER_UI_DOWNLOAD_SHA256=3d7ef5ddc59e10f132fe99771498f0f1ba7a2cbfb9585f9863d4191a574c96e7 \
    SWAGGER_UI_VERSION=3.6.0

###################################################################################################
# It's needed for lean mode where the controller is also an invoker
###################################################################################################
# If you change the docker version here, it has implications on invoker runc support.
# Docker server version and the invoker docker version must be the same to enable runc usage.
# If this cannot be guaranteed, set `invoker_use_runc: false` in the ansible env.
ENV DOCKER_VERSION=18.06.3-ce

RUN apt-get -y install openssl

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz && \
    tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
    tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
    rm -f docker-${DOCKER_VERSION}.tgz && \
    chmod +x /usr/bin/docker && \
    chmod +x /usr/bin/docker-runc
##################################################################################################

# Install swagger-ui
RUN curl -sSL -o swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz --no-verbose https://github.com/swagger-api/swagger-ui/archive/v${SWAGGER_UI_VERSION}.tar.gz && \
    echo "${SWAGGER_UI_DOWNLOAD_SHA256}  swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz" | sha256sum -c - && \
    mkdir swagger-ui && \
    tar zxf swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-${SWAGGER_UI_VERSION}/dist && \
    rm swagger-ui-v${SWAGGER_UI_VERSION}.tar.gz && \
    sed -i s#http://petstore.swagger.io/v2/swagger.json#/api/v1/api-docs#g /swagger-ui/index.html

# Copy app jars
ADD build/distributions/controller.tar /

COPY init.sh /
RUN chmod +x init.sh

RUN adduser --disabled-password --disabled-login --gecos '' --uid ${UID} --home /home/${NOT_ROOT_USER} ${NOT_ROOT_USER}

# It is possible to run as non root if you dont need invoker capabilities out of the controller today
# When running it as a non-root user this has implications on the standard directory where runc stores its data.
# The non-root user should have access on the directory and corresponding permission to make changes on it.
#USER ${NOT_ROOT_USER}

EXPOSE 8080
CMD ["./init.sh", "0"]
