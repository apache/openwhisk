#!/bin/bash
#
# generate default.props needed for CLI pip distribution
#
#
WHISK_HOME=$1
: ${WHISK_HOME:?"WHISK_HOME must be set and non-empty"}
cd "$WHISK_HOME"

VERSION_DATE=`fgrep whisk.version.date whisk.properties | cut -d' ' -f3`
CLI_API_HOST=`fgrep cli.api.host= whisk.properties | cut -d'=' -f2`

echo "CLI_API_HOST=$CLI_API_HOST"
echo "WHISK_VERSION_DATE=$VERSION_DATE"
