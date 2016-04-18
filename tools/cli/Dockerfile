FROM buildpack-deps:trusty

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install basic Python dependencies
RUN apt-get -y purge && \
    apt-get -y update && \
    apt-get -y install --fix-missing python2.7 python-distribute python-pip
RUN apt-get clean

ADD build/tmp /cli
RUN cd /cli && python setup.py sdist