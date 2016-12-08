FROM nodejsactionbase

# based on https://github.com/nodejs/docker-node
ENV NODE_VERSION 0.12.17
RUN curl -SLO "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
  && tar -xzf "node-v$NODE_VERSION-linux-x64.tar.gz" -C /usr/local --strip-components=1 \
  && rm "node-v$NODE_VERSION-linux-x64.tar.gz"

WORKDIR /nodejsAction

# Install app dependencies
RUN rm -rf .project .settings build.xml Dockerfile README node_modules logs
RUN npm install .
RUN npm install \
apn@1.7.4 \
async@1.5.2 \
body-parser@1.15.2 \
btoa@1.1.2 \
cheerio@0.20.0 \
cloudant@1.4.1 \
commander@2.7.0 \
consul@0.18.1 \
cookie-parser@1.3.4 \
cradle@0.6.7 \
errorhandler@1.3.5 \
express@4.14.0 \
express-session@1.11.1 \
gm@1.20.0 \
jade@1.9.2 \
log4js@0.6.38 \
merge@1.2.0 \
moment@2.8.1 \
mustache@2.1.3 \
nano@5.10.0 \
node-uuid@1.4.2 \
oauth2-server@2.4.0 \
openwhisk@2.6.0 \
process@0.11.0 \
request@2.79.0 \
rimraf@2.5.1 \
semver@4.3.6 \
serve-favicon@2.2.0 \
socket.io@1.3.5 \
socket.io-client@1.3.5 \
superagent@1.3.0 \
swagger-tools@0.8.7 \
tmp@0.0.28 \
watson-developer-cloud@1.4.1 \
when@3.7.3 \
ws@1.1.0 \
xml2js@0.4.15 \
xmlhttprequest@1.7.0 \
yauzl@2.3.1

# See app.js
CMD node app.js
