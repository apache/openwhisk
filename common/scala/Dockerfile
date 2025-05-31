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
# if you change version of openjdk, also update tools/github/setup.sh to download the corresponding jdk
# NOTE:
# OpenWhisk will use a 21-jre multi arch image, compilation will be done with a jdk 17 temurin based image.
# as wsk CLI is compiled against glibc we need touse a GLIBC based JRE Image (alpine it is not GLIBC based)
FROM eclipse-temurin:21-jre

ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# Install curl, bash, sed
RUN apt-get update && \
    apt-get install -y curl bash sed openssl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir /logs

COPY transformEnvironment.sh /
RUN chmod +x transformEnvironment.sh

COPY copyJMXFiles.sh /
RUN chmod +x copyJMXFiles.sh
