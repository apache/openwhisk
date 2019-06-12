#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -e

PASSWORD="openwhisk"

if [ "$#" -lt 3 ]; then
    echo "usage: $0 <common name: host or ip> [server|client] <scriptdir> <OPTIONAL:TrustorePassword> <OPTIONAL:generateKey>"
    exit
fi
CN=$1
TYPE=$2
SCRIPTDIR=$3
export TRUSTSTORE_PASSWORD=${4:-PASSWORD}
NAME_PREFIX=$5
GENKEY=$6


## generates a (self-signed) certificate
if [[ -n $GENKEY ]]
then
  openssl genrsa -out "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-key.pem" 2048
fi
function gen_csr(){
  echo generating server certificate request
  openssl req -new \
      -key "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-key.pem" \
      -nodes \
      -subj "/C=US/ST=NY/L=Yorktown/O=OpenWhisk/CN=$CN" \
      -out "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-request.csr"
}
function gen_cert(){
  echo generating self-signed password-less server certificate
  openssl x509 -req \
      -in "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-request.csr" \
      -signkey "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-key.pem" \
      -out "${SCRIPTDIR}/${NAME_PREFIX}openwhisk-server-cert.pem" \
      -days 365
}

function gen_p12_keystore(){
  openssl pkcs12 -export -name $CN \
       -passout pass:$TRUSTSTORE_PASSWORD \
       -in "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-cert.pem" \
       -inkey "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-key.pem" \
       -out "$SCRIPTDIR/${NAME_PREFIX}openwhisk-keystore.p12"
}

if [ "$TYPE" == "server_with_JKS_keystore" ]; then
  gen_csr
  gen_cert
  echo generate new key and place it in the keystore
  keytool -genkey -v \
    -alias $CN \
    -dname "C=US,ST=NY,L=Yorktown,O=OpenWhisk,CN=$CN" \
    -keystore "${SCRIPTDIR}/${NAME_PREFIX}keystore.jks" \
    -keypass:env TRUSTSTORE_PASSWORD \
    -storepass:env TRUSTSTORE_PASSWORD \
    -keyalg RSA \
    -ext KeyUsage:critical="keyCertSign" \
    -ext BasicConstraints:critical="ca:true" \
    -validity 365
  echo export private key from the keystore
  keytool -keystore "${SCRIPTDIR}/${NAME_PREFIX}keystore.jks" -alias $CN -certreq -file "${SCRIPTDIR}/${NAME_PREFIX}cert-file" -storepass:env TRUSTSTORE_PASSWORD
  echo sign the certificate with private key
  openssl x509 -req -CA "${SCRIPTDIR}/${NAME_PREFIX}openwhisk-server-cert.pem" -CAkey "$SCRIPTDIR/${NAME_PREFIX}openwhisk-server-key.pem" -in "${SCRIPTDIR}/${NAME_PREFIX}cert-file" -out "${SCRIPTDIR}/${NAME_PREFIX}cert-signed" -days 365 -CAcreateserial -passin pass:$TRUSTSTORE_PASSWORD
  echo import CA cert in the keystore
  keytool -keystore "${SCRIPTDIR}/${NAME_PREFIX}keystore.jks" -alias CARoot -import -file "${SCRIPTDIR}/${NAME_PREFIX}openwhisk-server-cert.pem" -storepass:env TRUSTSTORE_PASSWORD -noprompt
  echo import the private key in the keystore
  keytool -keystore "${SCRIPTDIR}/${NAME_PREFIX}keystore.jks" -alias $CN -import -file "${SCRIPTDIR}/${NAME_PREFIX}cert-signed" -storepass:env TRUSTSTORE_PASSWORD -noprompt

elif [ "$TYPE" == "server" ]; then
    gen_csr
    gen_cert
    echo generate keystore
    gen_p12_keystore
elif [ "$TYPE" == "p12_keystore_only" ]; then
    gen_csr
    gen_p12_keystore
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
