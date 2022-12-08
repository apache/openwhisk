#!/bin/bash

if [[ -z "$NGROK_DEBUG" ]]; then
   exit 1
fi

if [[ -z "$NGROK_TOKEN" ]]; then
  echo "Please set 'NGROK_TOKEN'"
  exit 2
fi

if [[ -z "$NGROK_PASSWORD" ]]; then
  echo "Please set 'NGROK_PASSWORD'"
  exit 3
fi

echo "### Install ngrok ###"
if ! test -e ./ngrok
then
  wget -q https://bin.equinox.io/c/4VmDzA7iaHb/ngrok-stable-linux-386.zip
  unzip ngrok-stable-linux-386.zip
  chmod +x ./ngrok
fi

echo "### Update user: $USER password ###"
echo -e "$NGROK_PASSWORD\n$NGROK_PASSWORD" | sudo passwd "$USER"

echo "### Start ngrok proxy for 22 port ###"

rm -f .ngrok.log
./ngrok authtoken "$NGROK_TOKEN"
./ngrok tcp 22 --log ".ngrok.log" &

sleep 10
HAS_ERRORS=$(grep "command failed" < .ngrok.log)

if [[ -z "$HAS_ERRORS" ]]; then
  echo ""
  echo "=========================================="
  echo "To connect: $(grep -o -E "tcp://(.+)" < .ngrok.log | sed "s/tcp:\/\//ssh $USER@/" | sed "s/:/ -p /")"
  echo "=========================================="
else
  echo "$HAS_ERRORS"
  exit 4
fi

#!/bin/bash
echo "You have an hour to debug this build."
echo "Do touch /tmp/continue to continue."
echo "Do touch /tmp/abort to abort."
for i in $(seq 1 12) 
do 
   if test -e /tmp/continue ; then exit 0 ; fi
   if test -e /tmp/abort ; then exit 1 ; fi
   sleep 300; echo "$i/12 still waiting..."
done
