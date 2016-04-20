#!/bin/bash
#
# use the command line interface to install Watson package.
#
: ${WHISK_SYSTEM_AUTH:?"WHISK_SYSTEM_AUTH must be set and non-empty"}
AUTH_KEY=$WHISK_SYSTEM_AUTH

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
CATALOG_HOME=$SCRIPTDIR
source "$CATALOG_HOME/util.sh"

echo Installing Watson package.

createPackage watson \
    -a description "Actions for the Watson analytics APIs" \
    -a parameters '[ {"name":"username", "required":false}, {"name":"password", "required":false, "type":"password"} ]'

waitForAll

install "$CATALOG_HOME/watson/translate.js" \
    watson/translate \
    -a description 'Translate text' \
    -a parameters '[ {"name":"translateFrom", "required":false}, {"name":"translateTo", "required":false}, {"name":"translateParam", "required":false}, {"name":"username", "required":true, "bindTime":true}, {"name":"password", "required":true, "type":"password", "bindTime":true} ]' \
    -a sampleInput '{"translateFrom":"en", "translateTo":"fr", "payload":"Hello", "username":"XXX", "password":"XXX"}' \
    -a sampleOutput '{"payload":"Bonjour"}'

install "$CATALOG_HOME/watson/languageId.js" \
    watson/languageId \
    -a description 'Identify language' \
    -a parameters '[ {"name":"username", "required":true, "bindTime":true}, {"name":"password", "required":true, "type":"password", "bindTime":true}, {"name":"payload", "required":true} ]' \
    -a sampleInput '{"payload": "Bonjour", "username":"XXX", "password":"XXX"}' \
    -a sampleOutput '{"language": "French", "confidence": 1}'

install "$CATALOG_HOME/watson/textToSpeech.js" \
    watson/textToSpeech \
    -a description 'Synthesize text to spoken audio' \
    -a parameters '[
        {"name":"username", "required":true, "bindTime":true, "description":"The Watson service username"},
        {"name":"password", "required":true, "type":"password", "bindTime":true, "description":"The Watson service password"},
        {"name":"payload", "required":true, "description":"The text to be synthesized"},
        {"name":"voice", "required":false, "description":"The voice to be used for synthesis"},
        {"name":"accept", "required":false, "description":"The requested MIME type of the audio"},
        {"name":"encoding", "required":false, "description":"The encoding of the speech binary data"}]' \
    -a sampleInput '{"payload":"Hello, world.", "encoding":"base64", "accept":"audio/wav", "username":"XXX", "password":"XXX" }' \
    -a sampleOutput '{"payload":"<base64 encoding of a .wav file>", "encoding":"base64", "content_type":"audio/wav"}'

waitForAll

echo Watson package ERRORS = $ERRORS
exit $ERRORS
