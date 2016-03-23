FROM whisk/scala

COPY build/distributions/loadBalancer.tar ./
RUN tar xf loadBalancer.tar
CMD /loadBalancer/bin/loadBalancer

EXPOSE 8080

