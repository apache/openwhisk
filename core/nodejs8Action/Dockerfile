FROM buildpack-deps:xenial-scm

ENV DEBIAN_FRONTEND noninteractive

# Initial update and some basics.
RUN apt-get update && apt-get install -y imagemagick && apt-get install -y unzip

ADD ./nodejsAction /nodejsAction

# based on https://github.com/nodejs/docker-node


RUN groupadd --gid 1000 node \
  && useradd --uid 1000 --gid node --shell /bin/bash --create-home node


ENV NPM_CONFIG_LOGLEVEL info
ENV NODE_VERSION 8.0.0-test20170327db9805885fb817c5b36e5b2bb53b82c0765e438f

RUN curl -SLO "https://nodejs.org/download/test/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.gz" \
  && tar -xzf "node-v$NODE_VERSION-linux-x64.tar.gz" -C /usr/local --strip-components=1 \
  && rm "node-v$NODE_VERSION-linux-x64.tar.gz" \
  && ln -s /usr/local/bin/node /usr/local/bin/nodejs

ENV YARN_VERSION 0.21.3

RUN set -ex \
  && curl -fSL -o yarn.js "https://yarnpkg.com/downloads/$YARN_VERSION/yarn-$YARN_VERSION.js" \
  && mv yarn.js /usr/local/bin/yarn \
  && chmod +x /usr/local/bin/yarn

WORKDIR /nodejsAction

# Install app dependencies
RUN rm -rf .project .settings build.xml Dockerfile README node_modules logs
RUN npm install .

RUN npm install \
apn@2.1.2 \
async@2.1.4 \
body-parser@1.15.2 \
btoa@1.1.2 \
cheerio@0.22.0 \
cloudant@1.6.2 \
commander@2.9.0 \
consul@0.27.0 \
cookie-parser@1.4.3 \
cradle@0.7.1 \
errorhandler@1.5.0 \
express@4.14.0 \
express-session@1.14.2 \
glob@7.1.1 \
gm@1.23.0 \
lodash@4.17.2 \
log4js@0.6.38 \
iconv-lite@0.4.15 \
marked@0.3.6 \
merge@1.2.0 \
moment@2.17.0 \
mongodb@2.2.11 \
mustache@2.3.0 \
nano@6.2.0 \
node-uuid@1.4.7 \
nodemailer@2.6.4 \
oauth2-server@2.4.1 \
openwhisk@3.3.2 \
pkgcloud@1.4.0 \
process@0.11.9 \
pug@">=2.0.0-beta6 <2.0.1" \
redis@2.6.3 \
request@2.79.0 \
request-promise@4.1.1 \
rimraf@2.5.4 \
semver@5.3.0 \
sendgrid@4.7.1 \
serve-favicon@2.3.2 \
socket.io@1.6.0 \
socket.io-client@1.6.0 \
superagent@3.0.0 \
swagger-tools@0.10.1 \
tmp@0.0.31 \
twilio@2.11.1 \
underscore@1.8.3 \
uuid@3.0.0 \
validator@6.1.0 \
watson-developer-cloud@2.14.3 \
when@3.7.7 \
winston@2.3.0 \
ws@1.1.1 \
xml2js@0.4.17 \
xmlhttprequest@1.8.0 \
yauzl@2.7.0

# See app.js
CMD node app.js
