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

if [[ -z "$NGROK_DEBUG" ]] || [[ "$NGROK_DEBUG" == "false" ]]
then exit 0
fi

if [[ -z "$NGROK_TOKEN" ]]
then echo "Please set 'NGROK_TOKEN'"
     exit 1
fi

if [[ -z "$NGROK_PASSWORD" ]]
then echo "Please set 'NGROK_PASSWORD'"
     exit 1
fi

echo "### Install ngrok ###"
if ! test -e ./ngrok
then
  wget -q https://bin.equinox.io/c/4VmDzA7iaHb/ngrok-stable-linux-386.zip
  unzip ngrok-stable-linux-386.zip
  chmod +x ./ngrok
fi

echo "### Update user: $USER password ###"
echo -e "$NGROK_PASSWORD\n$NGROK_PASSWORD" | sudo passwd "$USER"

echo "### Start ngrok proxy for 22 port ###"

rm -f .ngrok.log
./ngrok authtoken "$NGROK_TOKEN"
./ngrok tcp 22 --log ".ngrok.log" &

sleep 10
HAS_ERRORS=$(grep "command failed" < .ngrok.log)

if [[ -z "$HAS_ERRORS" ]]; then
  MSG="To connect: $(grep -o -E "tcp://(.+)" < .ngrok.log | sed "s/tcp:\/\//ssh $USER@/" | sed "s/:/ -p /")"
  echo ""
  echo "=========================================="
  echo "$MSG"
  echo "=========================================="
  if test -n "$SLACK_WEBHOOK"
  then
      echo -n '{"text":' >/tmp/msg$$
      echo -n "$MSG" | jq -Rsa . >>/tmp/msg$$
      echo -n '}' >>/tmp/msg$$
      curl -X POST -H 'Content-type: application/json' --data "@/tmp/msg$$" "$SLACK_WEBHOOK"
  fi
else
  echo "$HAS_ERRORS"
  exit 1
fi
