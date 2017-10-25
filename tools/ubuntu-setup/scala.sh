#!/bin/bash
set -e
set -x

#wget www.scala-lang.org/files/archive/scala-2.11.6.deb -O /tmp/scala-2.11.6.deb
wget https://downloads.lightbend.com/scala/2.11.11/scala-2.11.11.deb -O /tmp/scala-2.11.11.deb
sudo dpkg -i /tmp/scala-2.11.11.deb
sudo apt-get update
sudo apt-get install -y scala
