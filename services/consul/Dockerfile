FROM whisk/scala

RUN cd / && mkdir consul && mkdir consul/data && \
apt-get install -y unzip && \
apt-get install -y bc && \
apt-get install -y python && \
cd /tmp && \
wget https://releases.hashicorp.com/consul/0.5.2/consul_0.5.2_linux_amd64.zip && \
mv consul_0.5.2_linux_amd64.zip consul.zip && \
cd /bin && \
unzip /tmp/consul.zip &&  \
chmod +x /bin/consul && \
rm /tmp/consul.zip && \
cd /consul && \
wget https://releases.hashicorp.com/consul/0.5.2/consul_0.5.2_web_ui.zip && \
unzip consul_0.5.2_web_ui.zip && \
mv dist ui && \ 
rm consul_0.5.2_web_ui.zip 

# Expose standard ports, as well as ports used by consul
EXPOSE 8300 8301 8301/udp 8302 8302/udp 8400 8500 53/udp

ADD ./config /consul/config/
ADD startconsulserver.sh .
ADD startconsulclient.sh .

RUN chmod 777 startconsulserver.sh 

