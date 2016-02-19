#!/bin/bash
#
# set environment variables which name cloudant databases used by the whisk installation
#

# Where am I? Get config.
SOURCE="${BASH_SOURCE[0]}"
WHISK_HOME="$( cd -P "$( dirname "$SOURCE" )" && pwd )"/..

# a local env file with custom credentials
LOCAL_CLOUDANT_ENV=$WHISK_HOME/cloudant-local.env
# or a master env file rooted up from the whisk home in a credentials directory
MASTER_CLOUDANT_ENV=$WHISK_HOME/../credentials/cloudant.sh

if [ -e "$LOCAL_CLOUDANT_ENV" ]; then
    source "$LOCAL_CLOUDANT_ENV"
else
  if [ -e "$MASTER_CLOUDANT_ENV" ]; then
    source "$MASTER_CLOUDANT_ENV"
  else
    echo "Cloudant environment cannot be set because credentials are not defined"
    exit 1
  fi
fi

if [ -z "$OPEN_WHISK_DB_USERNAME" ]; then
    echo "Cloudant username is not set"
    exit 1
fi

if [ -z "$OPEN_WHISK_DB_PASSWORD" ]; then
    echo "Cloudant password is not set"
    exit 1
fi

# database for storing whisk entities
CLOUDANT_WHISK_ACTIONS=${CLOUDANT_DB_PREFIX}whisks

# database for storing authentication keys
CLOUDANT_WHISK_AUTHS=subjects

# alias to transient vs immortal db
# - a transient db may be wiped regularly, so for local deployments each one may start with a fresh database
# - an immortal db should not be wiped, and survives re-deployments
CLOUDANT_TRANSIENT_DBS="$CLOUDANT_WHISK_ACTIONS"
CLOUDANT_IMMORTAL_DBS="$CLOUDANT_WHISK_AUTHS"