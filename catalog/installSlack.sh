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
    -a description "This package interacts with the Slack messaging service" \
    -a parameters '[ {"name":"username", "required":true, "bindTime":true, "description": "Your Slack username"}, {"name":"url", "required":true, "bindTime":true, "description": "Your webhook URL", "doclink": "https://api.slack.com/incoming-webhooks"},{"name":"channel", "required":true, "bindTime":true, "description": "The name of a Slack channel"}, {"name": "token", "description": "Your Slack oauth token", "doclink": "https://api.slack.com/docs/oauth"} ]'

waitForAll

install "$CATALOG_HOME/slack/post.js" \
    slack/post \
    -a description 'Post a message to Slack' \
    -a parameters '[ {"name":"text", "required":true, "description": "The message you wish to post"} ]' \
    -a sampleInput '{"username":"openwhisk", "text":"Hello OpenWhisk!", "channel":"myChannel", "url": "https://hooks.slack.com/services/XYZ/ABCDEFG/12345678"}'

waitForAll

echo Slack package ERRORS = $ERRORS
exit $ERRORS
