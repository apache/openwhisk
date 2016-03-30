#!/bin/bash
#
# use the command line interface to install Watson package.
#
: ${WHISK_SYSTEM_AUTH:?"WHISK_SYSTEM_AUTH must be set and non-empty"}
AUTH_KEY=$WHISK_SYSTEM_AUTH

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
source "$SCRIPTDIR/util.sh"
cd "$SCRIPTDIR/../bin"

echo Installing Watson package.

createPackage 'watson' \
    -a description "Actions for the Watson analytics APIs" \
    -a parameters '[ {"name":"username", "required":false}, {"name":"password", "required":false, "type":"password"} ]'

waitForAll

install 'watson/translate.js'       watson/translate \
    -a description 'Translate text' \
    -a parameters '[ {"name":"translateFrom", "required":false}, {"name":"translateTo", "required":false}, {"name":"translateParam", "required":false}, {"name":"username", "required":true, "bindTime":true}, {"name":"password", "required":true, "type":"password", "bindTime":true} ]' \
    -a sampleInput '{"translateFrom":"en", "translateTo":"fr", "payload":"Hello", "username":"XXX", "password":"XXX"}' \
    -a sampleOutput '{"payload":"Bonjour"}'

install 'watson/languageId.js'      watson/languageId \
    -a description 'Identify language' \
    -a parameters '[ {"name":"username", "required":true, "bindTime":true}, {"name":"password", "required":true, "type":"password", "bindTime":true}, {"name":"payload", "required":true} ]' \
    -a sampleInput '{"payload": "Bonjour", "username":"XXX", "password":"XXX"}' \
    -a sampleOutput '{"language": "French", "confidence": 1}'

waitForAll

echo Watson package ERRORS = $ERRORS
exit $ERRORS
