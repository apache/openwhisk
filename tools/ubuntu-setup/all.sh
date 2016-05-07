#!/bin/bash
SOURCE="${BASH_SOURCE[0]}"
SCRIPTDIR="$( dirname "$SOURCE" )"

ERRORS=0


function install() {
    "$1/$2" &
    pid=$!
    wait $pid
    status=$?
    printf "$pid finished with status $status \n\n"
    if [ $status -ne 0 ]
    then
        let ERRORS=ERRORS+1
    fi
}

echo "*** installing basics"
install "$SCRIPTDIR" misc.sh


echo "*** installing python dependences"
install "$SCRIPTDIR" pip.sh


echo "*** installing java"
install "$SCRIPTDIR" java8.sh


echo "*** installing ant"
install "$SCRIPTDIR" ant.sh


echo "*** install scala"
install "$SCRIPTDIR" scala.sh


echo "*** installing docker"
install "$SCRIPTDIR" docker.sh


echo "*** installing gradle"
install "$SCRIPTDIR" gradle.sh


echo "*** installing ansible"
install "$SCRIPTDIR" ansible.sh


echo install all with total errors number $ERRORS
exit $ERRORS
