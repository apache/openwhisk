FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update --fix-missing && \
  apt-get -y dist-upgrade && \
  apt-get -y install python2.7 && \
  apt-get -y install python-distribute python-pip && \
  apt-get clean

ADD build/tmp /cli
CMD cd /cli && python setup.py sdist