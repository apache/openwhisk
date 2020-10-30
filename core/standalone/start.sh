#!/bin/bash
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
IMAGE="${1:-openwhisk/standalone:nightly}"
shift
docker run --rm -d \
  -h openwhisk --name openwhisk \
  -p 3233:3233 -p 3232:3232 \
  -v /var/run/docker.sock:/var/run/docker.sock \
 "$IMAGE" "$@"
docker exec openwhisk waitready
case "$(uname)" in
 (Linux) xdg-open http://localhost:3232 ;;
 (Darwin) open http://localhost:3232 ;;
 (MINGW*) start http://localhost:3232 ;;
 (*) echo Please use http://localhost:3232 for playground ;;
esac
