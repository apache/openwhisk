FROM ubuntu:14.04
ENV DEBIAN_FRONTEND noninteractive

RUN apt-get install -y software-properties-common
RUN add-apt-repository -y ppa:ethereum/ethereum
RUN apt-get update
RUN apt-get install -y golang && \
    apt-get install -y git && \
    apt-get install -y unzip

RUN mkdir /registrator
COPY registrator-master.zip /registrator/
RUN cd /registrator && unzip registrator-master.zip && mkdir bin
ENV GOPATH=/registrator/registrator-master
ENV GOBIN=/registrator/registrator-master/bin
RUN cd /registrator/registrator-master && \
    go get && \
    go build -ldflags "-X main.Version $(cat VERSION)" -o /bin/registrator
