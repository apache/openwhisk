FROM whisk/scala

ENV CONSUL_VERSION 0.6.4

RUN cd / && mkdir consul && mkdir consul/data && \
apt-get install -y unzip && \
apt-get install -y bc && \
apt-get install -y python && \
cd /tmp && \
wget https://releases.hashicorp.com/consul/${CONSUL_VERSION}/consul_${CONSUL_VERSION}_linux_amd64.zip && \
mv consul_${CONSUL_VERSION}_linux_amd64.zip consul.zip && \
cd /bin && \
unzip /tmp/consul.zip &&  \
chmod +x /bin/consul && \
rm /tmp/consul.zip && \
cd /consul && \
mkdir ui && cd ui && \
wget https://releases.hashicorp.com/consul/${CONSUL_VERSION}/consul_${CONSUL_VERSION}_web_ui.zip && \
unzip consul_${CONSUL_VERSION}_web_ui.zip && \
rm consul_${CONSUL_VERSION}_web_ui.zip 

# Expose standard ports, as well as ports used by consul
EXPOSE 8300 8301 8301/udp 8302 8302/udp 8400 8500 53/udp

ADD ./config /consul/config/
ADD startconsulserver.sh .
ADD startconsulclient.sh .

RUN chmod 777 startconsulserver.sh 

