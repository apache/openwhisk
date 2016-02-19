#!/bin/bash
#
# use the command line interface to install standard actions deployed
# automatically.
#

: ${WHISK_SYSTEM_AUTH:?"WHISK_SYSTEM_AUTH must be set and non-empty"}
AUTH_KEY=$WHISK_SYSTEM_AUTH

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
source "$SCRIPTDIR/util.sh"
cd "$SCRIPTDIR/../bin"

echo Installing whisk.system entities.

createPackage system
createPackage util
createPackage samples

waitForAll

install utils/pipe.js             system/pipe -a system true

install utils/cat.js              util/cat -a description 'Concatenate array of strings'
install utils/sort.js             util/sort -a description 'Sort array'
install utils/head.js             util/head -a description 'Filter first K array elements and discard rest'
install utils/date.js             util/date -a description 'Get current date and time'

install samples/hello.js          samples/helloWorld -a description 'Print to the console' -a parameters '[{"name": "payload", "required":false}]'
install samples/greeting.js       samples/greeting -a description 'Print a friendly greeting' -a parameters '[{"name": "name", "required":false}, {"name": "place", "required":false}]'
install samples/wc.js             samples/wordCount -a description 'Count words in a string' -a parameters '[{"name": "payload", "required":false}]'
install samples/echo.js           samples/echo -a description 'Returns the input arguments, unchanged' -a parameters '[{"name": "payload", "required":false}]'

waitForAll

echo whisk.system entities ERRORS = $ERRORS
exit $ERRORS
