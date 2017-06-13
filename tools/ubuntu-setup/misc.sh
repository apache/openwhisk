#!/bin/bash
set -e
set -x

export DEBIAN_FRONTEND=noninteractive

echo "Etc/UTC" | sudo tee /etc/timezone
sudo dpkg-reconfigure --frontend noninteractive tzdata

sudo apt-get update -y
sudo apt-get install -y ntp

sudo service ntp restart
sudo ntpq -c lpeer

sudo apt-get -y install git
sudo apt-get -y install zip
