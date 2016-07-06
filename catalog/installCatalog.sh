#!/bin/bash
#
# Download the repository of openwhisk-catalog into the openwhisk home
# directory and install all the packages.
#

# The system authentication file is specified by the first parameter, which is mandatory.
WHISK_SYSTEM_AUTH_FILE=$1
: ${WHISK_SYSTEM_AUTH_FILE:?"WHISK_SYSTEM_AUTH_FILE must be set and non-empty"}

# The openwhisk catalog can be specified by the second parameter. The default one
# is the openwhisk-catalog repository in github.
WHISK_CATALOG_REPO=${2:-"https://github.com/openwhisk/openwhisk-catalog.git"}

# The openwhisk home directory can be specified by the third parameter. If not,
# we will look up the environment variable and then fetch the directory via the
# current location when necessary.
OPENWHISK_HOME=${3:-$OPENWHISK_HOME}
: ${OPENWHISK_HOME:="$(dirname "$(pwd)")"}

export OPENWHISK_HOME

# The openwhisk-catalog is saved in the openwhisk home directory.
cd "$OPENWHISK_HOME"
OPENWHISK_CATALOG="openwhisk-catalog"
if [ ! -d "$OPENWHISK_CATALOG" ]; then
    # git clone "$WHISK_CATALOG_REPO"
    echo "Please install the catalog from openwhisk-catalog."
    exit 1
fi
cd "$OPENWHISK_CATALOG/packages"
bash "installCatalog.sh" "$WHISK_SYSTEM_AUTH_FILE" "$OPENWHISK_HOME"
