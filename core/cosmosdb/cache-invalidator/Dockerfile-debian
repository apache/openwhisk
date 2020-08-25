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


# Copy app jars
ADD build/distributions/cache-invalidator.tar /

COPY init.sh /
RUN chmod +x init.sh

RUN adduser --disabled-password --disabled-login --gecos '' --uid ${UID} --home /home/${NOT_ROOT_USER} ${NOT_ROOT_USER}
USER ${NOT_ROOT_USER}

EXPOSE 8080
CMD ["./init.sh", "0"]
