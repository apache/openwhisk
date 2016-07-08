FROM whisk/scala

ENV CONSUL_VERSION 0.6.4

RUN cd / && mkdir consul && mkdir consul/data && \
apt-get update && \
apt-get install -y unzip bc python && \
cd /tmp && \
wget --no-verbose https://releases.hashicorp.com/consul/${CONSUL_VERSION}/consul_${CONSUL_VERSION}_linux_amd64.zip && \
mv consul_${CONSUL_VERSION}_linux_amd64.zip consul.zip && \
cd /bin && \
unzip /tmp/consul.zip &&  \
chmod +x /bin/consul && \
rm /tmp/consul.zip && \
cd /consul && \
mkdir ui && cd ui && \
wget --no-verbose https://releases.hashicorp.com/consul/${CONSUL_VERSION}/consul_${CONSUL_VERSION}_web_ui.zip && \
unzip consul_${CONSUL_VERSION}_web_ui.zip && \
rm consul_${CONSUL_VERSION}_web_ui.zip 

# Expose standard ports, as well as ports used by consul
EXPOSE 8300 8301 8301/udp 8302 8302/udp 8400 8500 53/udp

