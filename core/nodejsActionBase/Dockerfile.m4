FROM m4_ifdef(`S390X',`s390x/buildpack-deps:xenial-curl',`buildpack-deps:trusty-curl')

ENV DEBIAN_FRONTEND noninteractive

# Initial update and some basics.
RUN apt-get update \
 && apt-get install -y imagemagick unzip \
 && apt-get clean && rm -rf /var/lib/apt/lists/

ADD . /nodejsAction
