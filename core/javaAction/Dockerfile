FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get -y update
RUN apt-get -y dist-upgrade
RUN apt-get -y update

# Install Java 8
RUN apt-get -y install software-properties-common
RUN add-apt-repository -y ppa:webupd8team/java
RUN apt-get -y update
RUN echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo /usr/bin/debconf-set-selections
RUN apt-get -y install oracle-java8-installer

RUN apt-get clean

ADD . /javaAction

RUN rm -rf /javaAction/.classpath /javaAction/.gitignore /javaAction/.gradle /javaAction/.project /javaAction/.settings /javaAction/Dockerfile /javaAction/build

RUN cd /javaAction; ./gradlew oneJar

RUN rm -rf /javaAction/src

CMD ["java", "-jar", "/javaAction/build/libs/javaAction-all.jar"]
