#!/usr/bin/env python
import os
import subprocess
import sys
import tempfile
import urllib

# Compresses the contents of a folder and upload the result to Box.
# Run this script as:
#
# $ upload-logs.py LOG_DIR DEST_NAME
#
# e.g.:
#
# $ upload-logs.py /tmp/wsklogs logs-5512.tar.gz

def upload_file(local_file, remote_file):
    if remote_file[0] == '/':
        remote_file = remote_file[1:]

    subprocess.call([ "curl", "-X", "POST", "--data-binary", "@%s" % local_file, "http://wsklogfwd.mybluemix.net/upload?%s" % urllib.urlencode({ "name" : remote_file }) ])

def tar_gz_dir(dir_path):
    _, dst = tempfile.mkstemp(suffix = ".tar.gz")
    subprocess.call([ "tar", "-cvzf", dst, dir_path ])
    return dst

if __name__ == "__main__":
    dir_path = sys.argv[1]
    dst_path = sys.argv[2]

    if not os.path.isdir(dir_path):
        print "Directory doesn't exist: %s." % dir_path
        sys.exit(0)

    print "Compressing logs dir..."
    tar = tar_gz_dir(dir_path)
    print "Uploading to Box..."
    upload_file(tar, dst_path)
