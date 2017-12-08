FROM scala

ENV DEBIAN_FRONTEND noninteractive

# Install swagger-ui
RUN wget --no-verbose https://github.com/swagger-api/swagger-ui/archive/v3.6.0.tar.gz && \
    mkdir swagger-ui && \
    tar zxf v3.6.0.tar.gz -C /swagger-ui --strip-components=2 swagger-ui-3.6.0/dist && \
    rm v3.6.0.tar.gz && \
    perl -pi -w -e  's{http://petstore.swagger.io/v2/swagger.json}{/api/v1/api-docs}g;' /swagger-ui/index.html

#
# Copy app jars
COPY build/distributions/controller.tar /
RUN tar xf controller.tar

COPY init.sh /
RUN chmod +x init.sh

EXPOSE 8080
CMD ["init.sh", "0"]