FROM nodejsactionbase

# based on https://github.com/nodejs/docker-node
#ENV NODE_VERSION 6.10.2
#ENV NODE_URL_DOWNLOAD https://nodejs.org/dist
#RUN curl -SLO "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
#  && tar -xzf "node-v$NODE_VERSION-linux-x64.tar.gz" -C /usr/local --strip-components=1 \
#  && rm "node-v$NODE_VERSION-linux-x64.tar.gz"

ENV NODE_VERSION 6.10.2-rc.1
ENV NODE_URL_DOWNLOAD https://nodejs.org/download/rc

RUN curl -SLO "$NODE_URL_DOWNLOAD/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
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
apn@2.1.4 \
async@2.1.4 \
body-parser@1.17.1 \
btoa@1.1.2 \
cheerio@0.22.0 \
cloudant@1.7.1 \
commander@2.9.0 \
consul@0.28.0 \
cookie-parser@1.4.3 \
cradle@0.7.1 \
errorhandler@1.5.0 \
express@4.15.2 \
express-session@1.15.2 \
glob@7.1.1 \
gm@1.23.0 \
lodash@4.17.2 \
log4js@1.1.1 \
iconv-lite@0.4.15 \
marked@0.3.6 \
merge@1.2.0 \
moment@2.17.0 \
mongodb@2.2.25 \
mustache@2.3.0 \
nano@6.2.0 \
node-uuid@1.4.7 \
nodemailer@3.1.8 \
oauth2-server@2.4.1 \
openwhisk@3.4.1 \
pkgcloud@1.4.0 \
process@0.11.9 \
pug@">=2.0.0-beta11 <2.0.1" \
redis@2.7.1 \
request@2.79.0 \
request-promise@4.2.0 \
rimraf@2.6.1 \
semver@5.3.0 \
sendgrid@5.0.0 \
serve-favicon@2.4.2 \
socket.io@1.7.3 \
socket.io-client@1.7.3 \
superagent@3.5.2 \
swagger-tools@0.10.1 \
tmp@0.0.31 \
twilio@2.11.1 \
underscore@1.8.3 \
uuid@3.0.0 \
validator@6.1.0 \
watson-developer-cloud@2.29.0 \
when@3.7.8 \
winston@2.3.1 \
ws@2.2.2 \
xml2js@0.4.17 \
xmlhttprequest@1.8.0 \
yauzl@2.7.0

# See app.js
CMD node --expose-gc app.js
