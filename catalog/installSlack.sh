#!/bin/bash
#
# use the command line interface to install Slack package.
#
: ${WHISK_SYSTEM_AUTH:?"WHISK_SYSTEM_AUTH must be set and non-empty"}
AUTH_KEY=$WHISK_SYSTEM_AUTH

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
CATALOG_HOME=$SCRIPTDIR
source "$CATALOG_HOME/util.sh"

echo Installing Slack package.

createPackage slack \
    -a description "Package which contains actions to interact with the Slack messaging service"

waitForAll

install "$CATALOG_HOME/slack/post.js" \
    slack/post \
    -a description 'Posts a message to Slack' \
    -a parameters '[ {"name":"username", "required":true, "bindTime":true}, {"name":"text", "required":true}, {"name":"url", "required":true, "bindTime":true},{"name":"channel", "required":true, "bindTime":true} ]' \
    -a sampleInput '{"username":"whisk", "text":"Hello whisk!", "channel":"myChannel", "url": "https://hooks.slack.com/services/XYZ/ABCDEFG/12345678"}'

waitForAll

echo Slack package ERRORS = $ERRORS
exit $ERRORS
