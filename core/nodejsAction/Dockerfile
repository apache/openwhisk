FROM ubuntu:14.04

ENV DEBIAN_FRONTEND noninteractive

# Initial update and some basics.
# This odd double update seems necessary to get curl to download without 404 errors.
RUN apt-get update --fix-missing && \
  apt-get install -y wget && \
  apt-get update && \
  apt-get install -y curl && \
  apt-get update && \
  curl -sL https://deb.nodesource.com/setup_0.12 | bash - && \
  apt-get install -y nodejs

# Install Image Magick
RUN apt-get update && apt-get install -y imagemagick

# Install "standard" npm packages (above and beyond the list in the base
# whisk/js image
RUN npm install -g apn@1.7.4 \
async@1.5.2 \
cheerio@0.20.0 \
cloudant@1.4.1 \
mustache@2.1.3 \
rimraf@2.5.1 \
tmp@0.0.28 \
body-parser@1.12.0 \
btoa@1.1.2 \
commander@2.7.0 \
cookie-parser@1.3.4 \
cradle@0.6.7 \
errorhandler@1.3.5 \
express@4.12.2 \
express-session@1.11.1 \
jade@1.9.2 \
log4js@0.6.25 \
consul@0.18.1 \
merge@1.2.0 \
moment@2.8.1 \
nano@5.10.0 \
node-uuid@1.4.2 \
oauth2-server@2.4.0 \
process@0.11.0 \
request@2.60.0 \
semver@4.3.6 \
serve-favicon@2.2.0 \
socket.io@1.3.5 \
swagger-tools@0.8.7 \
mustache@2.1.3 \
when@3.7.3 \
superagent@1.3.0 \
yauzl@2.3.1 \
xml2js@0.4.15 \
watson-developer-cloud@0.9.29 \
xml2js@0.4.15 \
xmlhttprequest@1.7.0


# only package.json
ADD package.json /nodejsAction/
RUN cd /nodejsAction; npm install

ADD runner.js app.js /nodejsAction/
ADD src /nodejsAction/src/

# See app.js
CMD node /nodejsAction/app.js
