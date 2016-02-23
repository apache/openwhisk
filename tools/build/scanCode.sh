#!/bin/bash
#
# Scan for mandatory code conventions.
#

SOURCE="${BASH_SOURCE[0]}"
DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"
ROOT="$DIR/../.."

#
# Scan a file for tabs.  Fail if any tab is found.
#
function rejectTabs {
    f=$1
    TAB_OCCURRENCES=`grep -n "$(printf '\t')" $f`
    TAB_COUNT=$(echo -n $TAB_OCCURRENCES | wc -c)
    if [ "$TAB_COUNT" -gt 0 ]
    then
        echo $TAB_OCCURRENCES
        echo "ERROR: Code convention violated: Found a tab in " $f
        exit 1
    fi
}

#
# Fail if any javascript, java, scala, build/deploy.xml or markdown file has a tab
#
function checkForTabs {
    #root directory to scan
    root=$1

    for f in $(find "$root" -name "*.js" -o -name "*.java" -o -name "*.scala" -o -name "build.xml" -o -name "deploy.xml" -o -name "*.md" \
               | grep -Fv resources/swagger-ui \
               | grep -Fv consul/ui/static \
               | grep -Fv node_modules \
               | grep -Fv site-packages)
    do
        rejectTabs $f
    done
}

#
# Fail if there are any symlinks found with the exception of wsk and wskadmin
#
function checkForLinks {
   #root directory to scan
    root=$1

    for f in $(find "$root" -type l | grep -v node_modules | grep -v bin/wsk)
    do
        echo "Rejecting because of symlink $f"
        ls -l $f
        exit 1
    done
}

checkForTabs "$ROOT"
checkForLinks "$ROOT"


