#!/bin/bash
#
# use the command line interface to install Weather.com package.
#
: ${WHISK_SYSTEM_AUTH:?"WHISK_SYSTEM_AUTH must be set and non-empty"}
AUTH_KEY=$WHISK_SYSTEM_AUTH

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
CATALOG_HOME=$SCRIPTDIR
source "$CATALOG_HOME/util.sh"

echo Installing Weather package.

createPackage weather \
    -a description "Services from IBM Weather Insights" \
    -a parameters '[ {"name":"apiKey", "required":false, "bindTime":true} ]'

waitForAll

install "$CATALOG_HOME/weather/forecast.js" \
    weather/forecast \
    -a description 'IBM Weather Insights 10-day forecast' \
    -a parameters '[ {"name":"latitude", "required":true}, {"name":"longitude", "required":true},{"name":"language", "required":false},{"name":"units", "required":false}, {"name":"timePeriod", "required":false}, {"name":"apiKey", "required":true, "type":"password", "bindTime":true} ]' \
    -a sampleInput '{"latitude":"34.063", "longitude":"-84.217", "apiKey":"XXX"}' \
    -a sampleOutput '{"forecasts":[ {"dow":"Monday", "min_temp":30, "max_temp":38, "narrative":"Cloudy"} ]}'

waitForAll

echo Weather package ERRORS = $ERRORS
exit $ERRORS
