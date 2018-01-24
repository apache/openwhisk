#!/bin/bash
set -e
set -x
<<<<<<< HEAD

if [ "$(lsb_release -cs)" == "trusty" ]; then
    sudo apt-get install -y software-properties-common python-software-properties
    sudo add-apt-repository ppa:jonathonf/openjdk
    sudo apt-get update
fi

sudo apt-get install openjdk-8-jdk -y
=======
arch=`uname -m`
if [ $arch == "ppc64le" ]
then
   sudo apt-get update -y
   sudo apt-get install openjdk-8-jdk -y
else
   sudo apt-get install -y software-properties-common
   sudo add-apt-repository -y ppa:webupd8team/java
   sudo apt-get update -y
   echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo /usr/bin/debconf-set-selections
   sudo apt-get install -y oracle-java8-installer
fi
>>>>>>> Changes needed to support ppc64le
