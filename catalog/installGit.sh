#!/bin/bash
#
# use the command line interface to install Git package.
#
: ${WHISK_SYSTEM_AUTH:?"WHISK_SYSTEM_AUTH must be set and non-empty"}
AUTH_KEY=$WHISK_SYSTEM_AUTH

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
CATALOG_HOME=$SCRIPTDIR
source "$CATALOG_HOME/util.sh"

echo Installing Git package.

createPackage github \
    -a description "Package which contains actions and feeds to interact with Github"

waitForAll

install "$CATALOG_HOME/github/webhook.js" \
    github/webhook \
    -a feed true \
    -a description 'Creates a webhook on github to be notified on selected changes' \
    -a parameters '[ {"name":"username", "required":true, "bindTime":true}, {"name":"repository", "required":true, "bindTime":true}, {"name":"accessToken", "required":true, "bindTime":true},{"name":"events", "required":true} ]' \
    -a sampleInput '{"username":"whisk", "repository":"WhiskRepository", "accessToken":"123ABCXYZ", "events": "push,commit,delete"}'

waitForAll

echo Git package ERRORS = $ERRORS
exit $ERRORS
