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

FROM openwhisk/action-nodejs-v12:1.14.0-incubating AS ownode

FROM weaveworks/ignite-centos

RUN curl -sL https://rpm.nodesource.com/setup_10.x | bash -
RUN sudo yum -y install nodejs

COPY --from=ownode /nodejsAction /nodejsAction
COPY --from=ownode /node_modules /node_modules
COPY ./action.service /etc/systemd/system/

RUN chmod 664 /etc/systemd/system/action.service \
    && systemctl enable action.service
