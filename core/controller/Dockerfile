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

# Install swagger-ui
RUN curl -sSL -o swagger-ui-v3.6.0.tar.gz --no-verbose https://github.com/swagger-api/swagger-ui/archive/v3.6.0.tar.gz && \
    mkdir swagger-ui && \
    tar zxf swagger-ui-v3.6.0.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-3.6.0/dist && \
    rm swagger-ui-v3.6.0.tar.gz && \
    sed -i s#http://petstore.swagger.io/v2/swagger.json#/api/v1/api-docs#g /swagger-ui/index.html

#
# Copy app jars
ADD build/distributions/controller.tar /

COPY init.sh /
RUN chmod +x init.sh

EXPOSE 8080
CMD ["./init.sh", "0"]
