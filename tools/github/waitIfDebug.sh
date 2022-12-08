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
   echo "$i/60 still waiting..."
   sleep 60; 
done
killall ngrok
rm -f .ngrok.log /tmp/continue /tmp/abort
exit $EXIT
