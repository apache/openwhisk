FROM whisk/scala

EXPOSE 8080

#
# Copy app jars
COPY loadBalancer.jar loadBalancer-libs.tar startLoadBalancer.sh ./

RUN tar xf loadBalancer-libs.tar && rm loadBalancer-libs.tar && \
chmod +x startLoadBalancer.sh

