#!/bin/bash
#
# utility functions used when installing standard whisk assets during deployment
#
# Note use of --apihost, this is needed in case of a b/g swap since the router may not be
# updated yet and there may be a breaking change in the API. All tests should go through edge.

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"
OPENWHISK_HOME=${OPENWHISK_HOME:-$SCRIPTDIR/..}

WHISKPROPS_FILE="$OPENWHISK_HOME/whisk.properties"
if [ ! -f "$WHISKPROPS_FILE" ]; then
    echo "whisk properties file not found $WHISKPROPS_FILE"
    exit 1
fi
EDGE_HOST=`fgrep edge.host= "$WHISKPROPS_FILE" | cut -d'=' -f2`

USE_PYTHON_CLI=false

function createPackage() {
    PACKAGE_NAME=$1
    REST=("${@:2}")
    if [ "$USE_PYTHON_CLI" = true ]; then
        CMD_ARRAY=($PYTHON "$OPENWHISK_HOME/bin/wsk" -i --apihost "$EDGE_HOST" package update --auth "$AUTH_KEY" --shared yes "$PACKAGE_NAME" "${REST[@]}")
    else
        CMD_ARRAY=("$OPENWHISK_HOME/bin/go-cli/wsk" -i --apihost "$EDGE_HOST" package update --auth "$AUTH_KEY" --shared yes "$PACKAGE_NAME" "${REST[@]}")
    fi
    export WSK_CONFIG_FILE= # override local property file to avoid namespace clashes
    "${CMD_ARRAY[@]}" &
    PID=$!
    PIDS+=($PID)
    echo "Creating package $PACKAGE_NAME with pid $PID"
}

function install() {
    RELATIVE_PATH=$1
    ACTION_NAME=$2
    REST=("${@:3}")
    if [ "$USE_PYTHON_CLI" = true ]; then
        CMD_ARRAY=($PYTHON "$OPENWHISK_HOME/bin/wsk" -i --apihost "$EDGE_HOST" action update --auth "$AUTH_KEY" --shared yes "$ACTION_NAME" "$RELATIVE_PATH" "${REST[@]}")
    else
        CMD_ARRAY=("$OPENWHISK_HOME/bin/go-cli/wsk" -i --apihost "$EDGE_HOST" action update --auth "$AUTH_KEY" --shared yes "$ACTION_NAME" "$RELATIVE_PATH" "${REST[@]}")
    fi
    export WSK_CONFIG_FILE= # override local property file to avoid namespace clashes
    "${CMD_ARRAY[@]}" &
    PID=$!
    PIDS+=($PID)
    echo "Installing $ACTION_NAME with pid $PID"
}

function runPackageInstallScript() {
    "$1/$2" &
    PID=$!
    PIDS+=($PID)
    echo "Installing package $2 with pid $PID"
}

# PIDS is the list of ongoing processes and ERRORS the total number of processes that failed
PIDS=()
ERRORS=0

# Waits for all processes in PIDS and clears it - updating ERRORS for each non-zero status code
function waitForAll() {
    for pid in ${PIDS[@]}; do
        wait $pid
        STATUS=$?
        echo "$pid finished with status $STATUS"
        if [ $STATUS -ne 0 ]
        then
            let ERRORS=ERRORS+1
        fi
    done
    PIDS=()
}
