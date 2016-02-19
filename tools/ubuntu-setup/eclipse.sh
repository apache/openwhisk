#!/bin/bash
#
cd /tmp
rm -f eclipse*
wget http://eclipse.bluemix.net/packages/mars.1/data/eclipse-inst-linux64.tar.gz
zcat eclipse-inst-linux64.tar.gz | tar xvf - 
