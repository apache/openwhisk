#!/bin/bash

openssl req -new -x509 -keyout ca-key -out ca-cert -days 365
keytool -keystore server.keystore.jks -alias localhost -validity 365 -genkey -keyalg RSA
keytool -keystore server.keystore.jks -alias localhost -certreq -file cert-file
openssl x509 -req -CA ca-cert -CAkey ca-key -in cert-file -out cert-signed -days 365 -CAcreateserial -passin pass:openwhisk
keytool -keystore server.keystore.jks -alias CARoot -import -file ca-cert
keytool -keystore server.keystore.jks -alias localhost -import -file cert-signed
