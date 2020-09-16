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
FROM adoptopenjdk/openjdk11-openj9:x86_64-debian-jdk-11.0.8_10_openj9-0.21.0-slim

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

#use cloudfront + https for packages
RUN echo 'deb https://cloudfront.debian.net/debian/ stable main\n\
deb https://cloudfront.debian.net/debian-security/ stable/updates main'\
> /etc/apt/sources.list

RUN apt-get -y install --no-install-recommends \
      sed curl bash && apt-get -y update && apt-get -y upgrade

RUN mkdir /logs

COPY transformEnvironment.sh /
RUN chmod +x transformEnvironment.sh

COPY copyJMXFiles.sh /
RUN chmod +x copyJMXFiles.sh
