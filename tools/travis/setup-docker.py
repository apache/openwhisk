#!/usr/bin/env python
"""Executable Python script for setting up docker daemon.

Add docker daemon configuration options in /etc/docker/daemon.json

  Run this script as:
  $python setup-docker.py

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""

from __future__ import print_function

import json
import traceback

DOCKER_DAEMON_FILE = "/etc/docker/daemon.json"

# Read the file.

DOCKER_OPTS = {
    "hosts": [
        "tcp://0.0.0.0:4243",
        "unix:///var/run/docker.sock"
    ],
    "storage-driver": "overlay",
    "userns-remap": "default"
}


def get_daemon_content():
    data = {}
    with open(DOCKER_DAEMON_FILE) as json_file:
        data = json.load(json_file)
    return data


def add_content(data):
    for config in DOCKER_OPTS.items():
        # config will be a tuple of key, value
        # ('hosts', ['tcp://0.0.0.0:4243', 'unix:///var/run/docker.sock'])
        key, value = config
        data[key] = value
    return data


def write_to_daemon_conf(data):
    try:
        with open(DOCKER_DAEMON_FILE, 'w') as fp:
            json.dump(data, fp)
    except Exception as e:
        print("Failed to write to daemon file")
        print(e)
        traceback.print_exc()


if __name__ == "__main__":
    current_data = get_daemon_content()
    print(current_data)
    updated_data = add_content(current_data)
    print(updated_data)
    write_to_daemon_conf(updated_data)
    print("Successfully Configured Docker daemon.json")
