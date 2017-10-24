#!/bin/bash

set -e

SCRIPTDIR="$(cd $(dirname "$0")/ && pwd)"

if [ "$#" -lt 2 ]; then
    echo "usage: $0 <common name: host or ip> [server|client]"
    exit
fi
CN=$1
TYPE=$2
PASSWORD="openwhisk"

## generates a (self-signed) certificate

## uncomment to regenerate the key
#openssl genrsa -out "$SCRIPTDIR/openwhisk-server-key.pem" 2048

if [ "$TYPE" == "server" ]; then
    echo generating server certificate request
    openssl req -new \
        -key "$SCRIPTDIR/openwhisk-server-key.pem" \
        -nodes \
        -subj "/C=US/ST=NY/L=Yorktown/O=OpenWhisk/CN=$CN" \
        -out "$SCRIPTDIR/openwhisk-server-request.csr"

    echo generating self-signed password-less server certificate
    openssl x509 -req \
        -in "$SCRIPTDIR/openwhisk-server-request.csr" \
        -signkey "$SCRIPTDIR/openwhisk-server-key.pem" \
        -out "$SCRIPTDIR/openwhisk-server-cert.pem" \
        -days 365
else
    echo generating client ca key
    openssl genrsa -aes256 -passout pass:$PASSWORD -out "$SCRIPTDIR/openwhisk-client-ca-key.pem" 2048

    echo generating client ca request
    openssl req -new \
    -key "$SCRIPTDIR/openwhisk-client-ca-key.pem" \
    -passin pass:$PASSWORD \
    -subj "/C=US/ST=NY/L=Yorktown/O=OpenWhisk/CN=$CN" \
    -out "$SCRIPTDIR/openwhisk-client-ca.csr"

    echo generating client ca pem
    openssl x509 -req \
    -in "$SCRIPTDIR/openwhisk-client-ca.csr" \
    -signkey "$SCRIPTDIR/openwhisk-client-ca-key.pem" \
    -passin pass:$PASSWORD \
    -out "$SCRIPTDIR/openwhisk-client-ca-cert.pem" \
    -days 365 -sha1 -extensions v3_ca

    echo generating client key
    openssl genrsa -aes256 -passout pass:$PASSWORD -out "$SCRIPTDIR/openwhisk-client-key.pem" 2048

    echo generating client certificate csr file
    openssl req -new \
    -key "$SCRIPTDIR/openwhisk-client-key.pem" \
    -passin pass:$PASSWORD \
    -subj "/C=US/ST=NY/L=Yorktown/O=OpenWhisk/CN=guest" \
    -out "$SCRIPTDIR/openwhisk-client-certificate-request.csr"

    echo generating self-signed client certificate
    echo 01 > $SCRIPTDIR/openwhisk-client-ca-cert.srl
    openssl x509 -req \
    -in "$SCRIPTDIR/openwhisk-client-certificate-request.csr" \
    -CA "$SCRIPTDIR/openwhisk-client-ca-cert.pem" \
    -CAkey "$SCRIPTDIR/openwhisk-client-ca-key.pem" \
    -CAserial "$SCRIPTDIR/openwhisk-client-ca-cert.srl" \
    -passin pass:$PASSWORD \
    -out "$SCRIPTDIR/openwhisk-client-cert.pem" \
    -days 365 -sha1 -extensions v3_req

    echo remove client key\'s password
    openssl rsa \
    -in "$SCRIPTDIR/openwhisk-client-key.pem" \
    -passin pass:$PASSWORD \
    -out "$SCRIPTDIR/openwhisk-client-key.pem"
fi
