#!/bin/bash
set -e
set -x

export DEBIAN_FRONTEND=noninteractive

sudo apt-get update -y
sudo apt-get install -y ntp git zip tzdata lsb-release

echo "Etc/UTC" | sudo tee /etc/timezone
sudo dpkg-reconfigure --frontend noninteractive tzdata

sudo service ntp restart
sudo ntpq -c lpeer
