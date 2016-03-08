FROM whisk/nodejs

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
watson-developer-cloud@0.9.29 \
xml2js@0.4.15 \
xmlhttprequest@1.7.0


# App
ADD . /nodejsAction

# Install app dependencies
RUN cd /nodejsAction; rm -rf .project .settings build.xml Dockerfile README node_modules logs && ln -s /usr/lib/node_modules node_modules 
RUN cd /nodejsAction; npm install .

# See app.js
CMD ["/bin/bash", "-c", "cd nodejsAction && node ./app.js"]
