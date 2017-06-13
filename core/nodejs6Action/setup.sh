#!/bin/bash
set -ex

curl -SLO "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
  && tar -xzf "node-v$NODE_VERSION-linux-x64.tar.gz" -C /usr/local --strip-components=1 \
  && rm "node-v$NODE_VERSION-linux-x64.tar.gz"


# workaround for this: https://github.com/npm/npm/issues/9863
#cd $(npm root -g)/npm \
# && npm install fs-extra \
# && sed -i -e s/graceful-fs/fs-extra/ -e s/fs\.rename/fs\.move/ ./lib/utils/rename.js


# Install app dependencies
rm -rf .project .settings build.xml Dockerfile README node_modules logs
npm install .

npm install --save \
apn@2.1.4 \
async@2.4.1 \
body-parser@1.17.2 \
btoa@1.1.2 \
cheerio@0.22.0 \
cloudant@1.8.0 \
commander@2.9.0 \
consul@0.29.0 \
cookie-parser@1.4.3 \
cradle@0.7.1 \
errorhandler@1.5.0 \
express@4.15.3 \
express-session@1.15.3 \
glob@7.1.2 \
gm@1.23.0 \
lodash@4.17.4 \
log4js@0.6.38 \
iconv-lite@0.4.17 \
marked@0.3.6 \
merge@1.2.0 \
moment@2.18.1 \
mongodb@2.2.28 \
mustache@2.3.0 \
nano@6.3.0 \
node-uuid@1.4.8 \
nodemailer@2.7.2 \
oauth2-server@2.4.1 \
openwhisk@3.6.0 \
pkgcloud@1.4.0 \
process@0.11.10 \
pug@">=2.0.0-beta6 <2.0.1" \
redis@2.7.1 \
request@2.81.0 \
request-promise@4.2.1 \
rimraf@2.6.1 \
semver@5.3.0 \
sendgrid@4.10.0 \
serve-favicon@2.4.3 \
socket.io@1.7.4 \
socket.io-client@1.7.4 \
superagent@3.5.2 \
swagger-tools@0.10.1 \
tmp@0.0.31 \
twilio@2.11.1 \
underscore@1.8.3 \
uuid@3.0.1 \
validator@6.3.0 \
watson-developer-cloud@2.32.1 \
when@3.7.8 \
winston@2.3.1 \
ws@1.1.4 \
xml2js@0.4.17 \
xmlhttprequest@1.8.0 \
yauzl@2.8.0