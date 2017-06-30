FROM nodejsactionbase

# based on https://github.com/nodejs/docker-node
ENV NODE_VERSION 6.11.0

WORKDIR /nodejsAction

ADD setup.sh .

RUN ./setup.sh

# See app.js
CMD node --expose-gc app.js
