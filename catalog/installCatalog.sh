#!/bin/bash
#
# use the command line interface to install standard actions deployed
# automatically
#
WHISK_SYSTEM_AUTH_FILE=$1
: ${WHISK_SYSTEM_AUTH_FILE:?"WHISK_SYSTEM_AUTH_FILE must be set and non-empty"}

export WHISK_SYSTEM_AUTH=`cat $WHISK_SYSTEM_AUTH_FILE`

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
source "$SCRIPTDIR/util.sh"

echo Installing open catalog

runPackageInstallScript "$SCRIPTDIR" installSystem.sh
runPackageInstallScript "$SCRIPTDIR" installGit.sh
runPackageInstallScript "$SCRIPTDIR" installSlack.sh
runPackageInstallScript "$SCRIPTDIR" installWatson.sh
runPackageInstallScript "$SCRIPTDIR" installWeather.sh
runPackageInstallScript "$SCRIPTDIR" installWebSocket.sh

waitForAll

echo open catalog ERRORS = $ERRORS
exit $ERRORS
