#LABEL consul

FROM s390x/alpine:3.6

RUN addgroup consul && \
    adduser -S -G consul consul

RUN apk add --no-cache ca-certificates curl libcap openssl dumb-init gnupg \
 && wget -O/bin/gosu "https://github.com/tianon/gosu/releases/download/1.10/gosu-s390x" \
 && wget -O/tmp/gosu.asc "https://github.com/tianon/gosu/releases/download/1.10/gosu-s390x.asc" \
 && gpg --keyserver ha.pool.sks-keyservers.net --recv-keys B42F6819007F00F88E364FD4036A9C25BF357DD4 \
 && gpg --batch --verify /tmp/gosu.asc /bin/gosu \
 && chmod +x /bin/gosu \
 && rm /tmp/gosu.asc \
 && apk del openssl gnupg

# TODO:  Move this image to a more supported storage location
COPY consul /bin/consul

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
