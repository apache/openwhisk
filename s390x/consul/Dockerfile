FROM s390x/alpine:3.6

LABEL image_name=consul tags=0.8.5,0.8,latest

RUN addgroup consul && \
    adduser -S -G consul consul

ENV GOPATH=/build/go \
    CONSUL_VERSION=0.8.5

RUN apk --no-cache add ca-certificates dumb-init openssl

COPY gox.diff /

RUN : \
 && apk --no-cache add --virtual build-deps bash git go make musl-dev \
#  The gox package needs to be patched to support S390X builds
 && go get github.com/mitchellh/gox \
 && cd $GOPATH/src/github.com/mitchellh/gox \
 && patch -p1 </gox.diff \
 && go build \
 && go install \
#  Here's where we get the actual Consul repository and switch versions
 && mkdir -p $GOPATH/src/github.com/hashicorp \
 && cd $GOPATH/src/github.com/hashicorp \
 && git clone https://github.com/hashicorp/consul.git \
 && cd consul \
 && git checkout -b v${CONSUL_VERSION} tags/v${CONSUL_VERSION}  \
#  This little change is needed to keep Make from over-writing our patched gox
 && sed -i -e 's/go get -u -v/go get -v/' GNUmakefile \
 && CONSUL_DEV=1 PATH=$PATH:$GOPATH/bin make \
 && mv ./bin/consul /bin/consul \
 && apk del build-deps \
 && rm -rf /gox.diff /build \
 && :

RUN apk add --no-cache libcap gnupg \
 && wget -O/bin/gosu "https://github.com/tianon/gosu/releases/download/1.10/gosu-s390x" \
 && wget -O/tmp/gosu.asc "https://github.com/tianon/gosu/releases/download/1.10/gosu-s390x.asc" \
 && gpg --keyserver ha.pool.sks-keyservers.net --recv-keys B42F6819007F00F88E364FD4036A9C25BF357DD4 \
 && gpg --batch --verify /tmp/gosu.asc /bin/gosu \
 && chmod +x /bin/gosu \
 && rm /tmp/gosu.asc \
 && apk del openssl gnupg

# The /consul/data dir is used by Consul to store state. The agent will be started
# with /consul/config as the configuration directory so you can add additional
# config files in that location.
RUN mkdir -p /consul/data && \
    mkdir -p /consul/config && \
    chown -R consul:consul /consul

# Expose the consul data directory as a volume since there's mutable state in there.
VOLUME /consul/data

# Server RPC is used for communication between Consul clients and servers for internal
# request forwarding.
EXPOSE 8300

# Serf LAN and WAN (WAN is used only by Consul servers) are used for gossip between
# Consul agents. LAN is within the datacenter and WAN is between just the Consul
# servers in all datacenters.
EXPOSE 8301 8301/udp 8302 8302/udp

# HTTP and DNS (both TCP and UDP) are the primary interfaces that applications
# use to interact with Consul.
EXPOSE 8500 8600 8600/udp

# Consul doesn't need root privileges so we run it as the consul user from the
# entry point script. The entry point script also uses dumb-init as the top-level
# process to reap any zombie processes created by Consul sub-processes.
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["docker-entrypoint.sh"]

# By default you'll get an insecure single-node development server that stores
# everything in RAM, exposes a web UI and HTTP endpoints, and bootstraps itself.
# Don't use this configuration for production.
CMD ["agent", "-dev", "-client", "0.0.0.0"]
