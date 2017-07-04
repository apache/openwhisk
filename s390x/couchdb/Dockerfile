FROM s390x/ubuntu:xenial
#LABEL couchdb

RUN apt-get -y update \
 && apt-get -y install couchdb \
 && apt-get -y clean \
 && rm -rf /var/lib/apt/lists/*

COPY docker.ini /etc/couchdb/local.d
RUN mkdir /var/run/couchdb

EXPOSE 5984

CMD ["couchdb"]
