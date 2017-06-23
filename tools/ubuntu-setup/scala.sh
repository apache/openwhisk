#!/bin/bash
set -e
set -x

wget www.scala-lang.org/files/archive/scala-2.11.6.deb -O /tmp/scala-2.11.6.deb
sudo dpkg -i /tmp/scala-2.11.6.deb
sudo apt-get update
sudo apt-get install -y scala