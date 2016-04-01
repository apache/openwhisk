#!/bin/bash
#
# set environment variables which name cloudant databases used by the whisk installation
#

# This script determines which DB configuration to use, out of three options:
#   - locally configured Cloudant connection
#   - locally configured CouchDB connection
#   - globally configured Cloudant connection
#
# The determination process is based on the existence of specific files.

# Where am I? Get config.
SOURCE="${BASH_SOURCE[0]}"
WHISK_HOME="$( cd -P "$( dirname "$SOURCE" )" && pwd )"/..

# A local env file with custom Cloudant credentials...
LOCAL_CLOUDANT_ENV=$WHISK_HOME/cloudant-local.env

# ...of a local env file with CouchDB credentials...
LOCAL_COUCHDB_ENV=$WHISK_HOME/couchdb-local.env

# ...or a master env file rooted up from the whisk home in a credentials directory.
MASTER_CLOUDANT_ENV=$WHISK_HOME/../credentials/cloudant.sh

if [ -e "$LOCAL_CLOUDANT_ENV" ]; then
    OPEN_WHISK_DB_PROVIDER="Cloudant"
    source "$LOCAL_CLOUDANT_ENV"
elif [ -e "$LOCAL_COUCHDB_ENV" ]; then
    OPEN_WHISK_DB_PROVIDER="CouchDB"
    source "$LOCAL_COUCHDB_ENV"
elif [ -e "$MASTER_CLOUDANT_ENV" ]; then
    OPEN_WHISK_DB_PROVIDER="Cloudant"
    source "$MASTER_CLOUDANT_ENV"
else
    echo "DB environment cannot be set because credentials are not defined, for either Cloudant or CouchDB."
    exit 1
fi

if [ "$OPEN_WHISK_DB_PROVIDER" == "Cloudant" ]; then
    if [ -z "$OPEN_WHISK_DB_USERNAME" ]; then
        echo "Cloudant username is not set"
        exit 1
    fi

    if [ -z "$OPEN_WHISK_DB_PASSWORD" ]; then
        echo "Cloudant password is not set"
        exit 1
    fi

    OPEN_WHISK_DB_HOST="${OPEN_WHISK_DB_USERNAME}.cloudant.com"
    OPEN_WHISK_DB_PORT=443
elif [ "$OPEN_WHISK_DB_PROVIDER" == "CouchDB" ]; then
    if [ -z "$OPEN_WHISK_DB_HOST" ]; then
        echo "CouchDB host is not set"
        exit 1
    fi

    if [ -z "$OPEN_WHISK_DB_PORT" ]; then
        echo "CouchDB port is not set"
        exit 1
    fi
    if [ -z "$OPEN_WHISK_DB_USERNAME" ]; then
        echo "CouchDB username is not set"
        exit 1
    fi

    if [ -z "$OPEN_WHISK_DB_PASSWORD" ]; then
        echo "CouchDB password is not set"
        exit 1
    fi
else
    echo "Unknown DB provider value '$OPEN_WHISK_DB_PROVIDER'."
    exit 1
fi

if [ -z "$OPEN_WHISK_DB_PROTOCOL" ]; then
    OPEN_WHISK_DB_PROTOCOL=https
fi

# database for storing whisk entities
DB_WHISK_ACTIONS=${DB_PREFIX}whisks

# database for storing authentication keys
DB_WHISK_AUTHS=subjects

# alias to transient vs immortal db
# - a transient db may be wiped regularly, so for local deployments each one may start with a fresh database
# - an immortal db should not be wiped, and survives re-deployments
DB_TRANSIENT_DBS="$DB_WHISK_ACTIONS"
DB_IMMORTAL_DBS="$DB_WHISK_AUTHS"
