FROM nodejsactionbase

ENV NODE_VERSION 6.11.0
ENV NODE_URL_DOWNLOAD https://nodejs.org/dist

RUN curl -SLO "$NODE_URL_DOWNLOAD/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
  && tar -xzf "node-v$NODE_VERSION-linux-x64.tar.gz" -C /usr/local --strip-components=1 \
  && rm "node-v$NODE_VERSION-linux-x64.tar.gz"

WORKDIR /nodejsAction

# Install app dependencies
RUN npm install .

RUN npm install \
apn@2.1.4 \
async@2.5.0 \
body-parser@1.17.2 \
btoa@1.1.2 \
cheerio@0.22.0 \
cloudant@1.8.0 \
commander@2.10.0 \
consul@0.29.0 \
cookie-parser@1.4.3 \
cradle@0.7.1 \
errorhandler@1.5.0 \
express@4.15.3 \
express-session@1.15.3 \
glob@7.1.2 \
gm@1.23.0 \
lodash@4.17.4 \
log4js@1.1.1 \
iconv-lite@0.4.18 \
marked@0.3.6 \
merge@1.2.0 \
moment@2.18.1 \
mongodb@2.2.29 \
mustache@2.3.0 \
nano@6.3.0 \
node-uuid@1.4.8 \
nodemailer@4.0.1 \
oauth2-server@2.4.1 \
openwhisk@3.6.0 \
pkgcloud@1.4.0 \
process@0.11.10 \
pug@">=2.0.0-beta11 <2.0.1" \
redis@2.7.1 \
request@2.81.0 \
request-promise@4.2.1 \
rimraf@2.6.1 \
semver@5.3.0 \
sendgrid@5.1.1 \
serve-favicon@2.4.3 \
socket.io@2.0.3 \
socket.io-client@2.0.3 \
superagent@3.5.2 \
swagger-tools@0.10.1 \
tmp@0.0.31 \
twilio@3.4.0 \
underscore@1.8.3 \
uuid@3.1.0 \
validator@7.2.0 \
watson-developer-cloud@2.32.1 \
when@3.7.8 \
winston@2.3.1 \
ws@3.0.0 \
xml2js@0.4.17 \
xmlhttprequest@1.8.0 \
yauzl@2.8.0 \
--save-exact \
&& npm cache clean

# See app.js
CMD node --expose-gc app.js
