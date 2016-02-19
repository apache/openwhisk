FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

# Expose standard ports
EXPOSE 8080

# Initial update and some basics.
# Get Java 8
# Install Scala
RUN apt-get update --fix-missing && \
  apt-get install -y wget && \
  apt-get update  && \
  apt-get install -y curl  && \
  apt-get install -y software-properties-common && \
  add-apt-repository -y ppa:webupd8team/java && \
  apt-get update -y && \
  echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo /usr/bin/debconf-set-selections && \
  apt-get install -y oracle-java8-installer && \
  wget www.scala-lang.org/files/archive/scala-2.11.6.deb && \
  dpkg -i scala-2.11.6.deb && \
  apt-get install -y scala && \
  rm scala-2.11.6.deb && \
  mkdir /logs
