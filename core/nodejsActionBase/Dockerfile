FROM buildpack-deps:trusty-curl

ENV DEBIAN_FRONTEND noninteractive

# Initial update and some basics.
RUN apt-get update && apt-get install -y imagemagick

ADD . /nodejsAction
