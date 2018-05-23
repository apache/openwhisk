# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

FROM scala

# Install swagger-ui
RUN curl -sSL -o swagger-ui-v3.6.0.tar.gz --no-verbose https://github.com/swagger-api/swagger-ui/archive/v3.6.0.tar.gz && \
    mkdir swagger-ui && \
    tar zxf swagger-ui-v3.6.0.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-3.6.0/dist && \
    rm swagger-ui-v3.6.0.tar.gz && \
    sed -i s#http://petstore.swagger.io/v2/swagger.json#/api/v1/api-docs#g /swagger-ui/index.html

#
# Copy app jars
ADD build/distributions/controller.tar /

COPY init.sh /
RUN chmod +x init.sh

EXPOSE 8080
CMD ["./init.sh", "0"]
