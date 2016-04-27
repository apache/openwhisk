#!/bin/bash
#
# use the command line interface to install websocket package.
#
: ${WHISK_SYSTEM_AUTH:?"WHISK_SYSTEM_AUTH must be set and non-empty"}
AUTH_KEY=$WHISK_SYSTEM_AUTH

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
CATALOG_HOME=$SCRIPTDIR
source "$CATALOG_HOME/util.sh"

echo Installing WebSocket package.

createPackage websocket \
    -a description "Utilities for communicating with WebSockets"
    -a parameters '[ {"name":"uri", "required":true, "bindTime":true} ]'

waitForAll

install "$CATALOG_HOME/websocket/sendWebSocketMessageAction.js" \
    websocket/send \
    -a description 'Send a message to a WebSocket' \
    -a parameters '[
      {
        "name": "uri",
        "required": true,
        "description": "The URI of the websocket server."
      },
      {
        "name": "payload",
        "required": true,
        "description": "The data you wish to send to the websocket server."
      }
    ]' \
    -a sampleInput '{"uri":"ws://MyAwesomeService.com/sweet/websocket", "payload":"Hi there, WebSocket!"}' \
    -a sampleOutput '{"result":{"payload":"Hi there, WebSocket!"},"status":"success","success":true}'

waitForAll

echo WebSocket package ERRORS = $ERRORS
exit $ERRORS
