#!/bin/bash
if ! test -e .ngrok.log
then exit 0
fi
echo "You have an hour to debug this build."
echo "Do touch /tmp/continue to continue."
echo "Do touch /tmp/abort to abort."
EXIT=0
for i in $(seq 1 60) 
do
   if test -e /tmp/continue ; then EXIT=0 ; break ; fi
   if test -e /tmp/abort ; then EXIT=1 ; break ; fi
   sleep 60; echo "$i/60 still waiting..."
done
killall ngrok
rm .ngrok.log
exit $EXIT
