FROM whisk/scala

ENV DEBIAN_FRONTEND noninteractive

# Install swagger-ui
RUN wget --no-verbose https://github.com/swagger-api/swagger-ui/archive/v2.1.4.tar.gz && \
    mkdir swagger-ui && \
    tar zxf v2.1.4.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-2.1.4/dist && \ 
    rm v2.1.4.tar.gz && \
    perl -pi -w -e  's{http://petstore.swagger.io/v2/swagger.json}{/api/v1/api-docs}g;' /swagger-ui/index.html

#
# Copy app jars
COPY build/distributions/controller.tar ./
RUN tar xf controller.tar
CMD controller/bin/controller >> /logs/${COMPONENT_NAME}_logs.log 2>&1

EXPOSE 8080
