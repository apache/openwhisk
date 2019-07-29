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

FROM invoker

ARG OW_ROOT_DIR

RUN mkdir -p /coverage/common && \
    mkdir -p /coverage/invoker && \
    mkdir -p "${OW_ROOT_DIR}/common/scala/build" && \
    mkdir -p "${OW_ROOT_DIR}/core/invoker/build" && \
    ln -s /coverage/common "${OW_ROOT_DIR}/common/scala/build/scoverage" && \
    ln -s /coverage/invoker "${OW_ROOT_DIR}/core/invoker/build/scoverage"

COPY build/tmp/docker-coverage /invoker/
