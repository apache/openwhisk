#!/bin/bash
set -e
set -x

if [ "$(lsb_release -cs)" == "trusty" ]; then
    sudo apt-get install -y software-properties-common python-software-properties
    sudo add-apt-repository ppa:jonathonf/openjdk
fi

sudo apt-get update
sudo apt-get install openjdk-8-jdk -y
