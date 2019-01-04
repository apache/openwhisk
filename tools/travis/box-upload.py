#!/usr/bin/env python
"""Executable Python script for compressing folders to Box.

Compresses the contents of a folder and upload the result to Box.

  Run this script as:
  $ upload-logs.py LOG_DIR DEST_NAME

  e.g.: $ upload-logs.py /tmp/wsklogs logs-5512.tar.gz

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

import os
import subprocess
import sys
import tempfile
import urllib
import humanize
import requests
import hashlib


def upload_file(local_file, remote_file):
    """Upload file."""
    if remote_file[0] == '/':
        remote_file = remote_file[1:]

    url = "http://DamCYhF8.mybluemix.net/upload?%s" % \
        urllib.urlencode({"name": remote_file})

    r = requests.post(url,
            headers={"Content-Type": "application/gzip"},
            data=open(local_file, 'rb'))

    print("Posting result", r)
    print(r.text)


def tar_gz_dir(dir_path):
    """Create TAR (ZIP) of path and its contents."""
    _, dst = tempfile.mkstemp(suffix=".tar.gz")
    subprocess.call(["tar", "-cvzf", dst, dir_path])
    return dst


def print_tarball_size(tarball):
    """Get and print the size of the tarball"""
    tarballsize = os.path.getsize(tarball)
    print("Size of tarball", tarball, "is", humanize.naturalsize(tarballsize))

    sha256_hash = hashlib.sha256()
    with open(tarball, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    print("SHA256 hash of tarball is", sha256_hash.hexdigest())


if __name__ == "__main__":
    dir_path = sys.argv[1]
    dst_path = sys.argv[2]

    if not os.path.isdir(dir_path):
        print("Directory doesn't exist: %s." % dir_path)
        sys.exit(0)

    print("Compressing logs dir...")
    tar = tar_gz_dir(dir_path)
    print_tarball_size(tar)

    print("Uploading to Box...")
    upload_file(tar, dst_path)
