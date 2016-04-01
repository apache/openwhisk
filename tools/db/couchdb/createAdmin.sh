#!/usr/bin/env bash

COUCHDB_PROT="https"
COUCHDB_HOST="localhost"
COUCHDB_PORT="6984"
COUCHDB_USER="whisk_admin"

usage() {
  echo "Usage: $0 -s PROT -h HOST -p PORT -u USER -P PASS" 1>&2;
  echo "  PROT defaults to ${COUCHDB_PROT}" 1>&2;
  echo "  HOST defaults to ${COUCHDB_HOST}" 1>&2;
  echo "  PORT defaults to ${COUCHDB_PORT}" 1>&2;
  echo "  USER defaults to ${COUCHDB_USER}" 1>&2;
  echo "  PASS is mandatory." 1>&2;
  exit 1;
}

while getopts ":s:h:p:u:P:" opt; do
  case "${opt}" in
    s)
      COUCHDB_PROT=${OPTARG}
      ;;
    h)
      COUCHDB_HOST=${OPTARG}
      ;;
    p)
      COUCHDB_PORT=${OPTARG}
      ;;
    u)
      COUCHDB_USER=${OPTARG}
      ;;
    P)
      COUCHDB_PASS=${OPTARG}
      ;;
    *)
      usage
      ;;
  esac
done

if [ -z "${COUCHDB_PASS}" ]; then
  usage
fi

shift $((OPTIND-1))

CURL="curl -k"

# Attempt to register the user as admin. This generally only works on fresh installs.
#   - an output of "" is a good sign.
#   - an output of {"error": ...} is not a good sign.
${CURL} -X PUT ${COUCHDB_PROT}://${COUCHDB_HOST}:${COUCHDB_PORT}/_config/admins/${COUCHDB_USER} -d "\"${COUCHDB_PASS}\""

# Disable reduce limit on views
${CURL} -X PUT ${COUCHDB_PROT}://${COUCHDB_HOST}:${COUCHDB_PORT}/_config/query_server_config/reduce_limit -d '"false"' -u ${COUCHDB_USER}:${COUCHDB_PASS}
