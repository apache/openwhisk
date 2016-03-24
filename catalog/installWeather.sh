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
    -a description "Services from The Weather Company" \
    -a parameters '[ {"name":"apiKey", "required":false} ]'

waitForAll

install "$CATALOG_HOME/weather/forecast.js" \
    weather/forecast \
    -a description 'Weather.com 10-day forecast' \
    -a parameters '[ {"name":"latitude", "required":true}, {"name":"longitude", "required":true}, {"name":"apiKey", "required":true, "type":"password", "bindTime":true} ]' \
    -a sampleInput '{"latitude":"34.063", "longitude":"-84.217", "apiKey":"XXX"}' \
    -a sampleOutput '{"forecasts":[ {"dow":"Monday", "min_temp":30, "max_temp":38, "narrative":"Cloudy"} ]}'

install 'weather/pollen.js'       weather/pollen \
    -a description 'Weather.com pollen information' \
    -a parameters '[{"name":"latitude", "required":true}, {"name":"longitude", "required":true}, {"name":"apiKey", "required":true, "type":"password", "bindTime":true}]'
    -a sampleInput '{"latitude":"34.063", "longitude":"-84.217", "apiKey":"XXX"}' \
	-a sampleOutput '{"pollenobservations":[{"class":"pollenobs", "loc_id":"ATL", "loc_nm":"Atlanta", "loc_st":"GA", "rpt_dt":"2016-03-29T12:26:00Z", "process_time_gmt":1396983306, "treenames":[{"tree_nm":"Oak"}], "total_pollen_cnt":1156, "pollenobservation":[{"pollen_type":"Tree","pollen_idx":"4","pollen_desc":"Very High"}]}]}'

waitForAll

echo Weather package ERRORS = $ERRORS
exit $ERRORS
