FROM whisk/nodejsactionbase

# based on https://github.com/nodejs/docker-node
ENV NODE_VERSION 6.2.0
RUN curl -SLO "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
  && tar -xzf "node-v$NODE_VERSION-linux-x64.tar.gz" -C /usr/local --strip-components=1 \
  && rm "node-v$NODE_VERSION-linux-x64.tar.gz"


# workaround for this: https://github.com/npm/npm/issues/9863
RUN cd $(npm root -g)/npm \
 && npm install fs-extra \
 && sed -i -e s/graceful-fs/fs-extra/ -e s/fs\.rename/fs\.move/ ./lib/utils/rename.js

WORKDIR /nodejsAction

# Install app dependencies
RUN rm -rf .project .settings build.xml Dockerfile README node_modules logs
RUN npm install .

RUN npm install \
apn@1.7.5 \
async@1.5.2 \
body-parser@1.15.1 \
btoa@1.1.2 \
cheerio@0.20.0 \
cloudant@1.4.1 \
commander@2.9.0 \
consul@0.25.0 \
cookie-parser@1.4.2 \
cradle@0.7.1 \
errorhandler@1.4.3 \
express@4.13.4 \
express-session@1.12.1 \
gm@1.22.0 \
log4js@0.6.36 \
merge@1.2.0 \
moment@2.13.0 \
mustache@2.2.1 \
nano@6.2.0 \
node-uuid@1.4.7 \
oauth2-server@2.4.1 \
process@0.11.3 \
pug@">2.0.0-alpha <2.0.1" \
request@2.72.0 \
rimraf@2.5.2 \
semver@5.1.0 \
serve-favicon@2.3.0 \
socket.io@1.4.6 \
socket.io-client@1.4.6 \
superagent@1.8.3 \
swagger-tools@0.10.1 \
tmp@0.0.28 \
watson-developer-cloud@1.8.0 \
when@3.7.7 \
ws@1.1.0 \
xml2js@0.4.16 \
xmlhttprequest@1.8.0 \
yauzl@2.4.2

# See app.js
CMD node app.js
