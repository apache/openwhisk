#!/bin/bash

set -e

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"

if [ "$#" -ne 1 ]; then
    echo "usage: $0 [common name: host or ip]"
fi
CN=$1

## generates a (self-signed) certificate

## uncomment to regenerate the key
#openssl genrsa -out "$SCRIPTDIR/openwhisk-key.pem" 2048

echo generating certificate request
openssl req -new \
    -key "$SCRIPTDIR/openwhisk-key.pem" \
    -nodes \
    -subj "/C=US/ST=NY/L=Yorktown/O=OpenWhisk/CN=$CN" \
    -out "$SCRIPTDIR/openwhisk-request.csr"

echo generating self-signed password-less certificate
openssl x509 -req \
    -in "$SCRIPTDIR/openwhisk-request.csr" \
    -signkey "$SCRIPTDIR/openwhisk-key.pem" \
    -out "$SCRIPTDIR/openwhisk-cert.pem" \
    -days 365
