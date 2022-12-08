#!/bin/bash
echo "You have an hour to debug this build."
echo "Remove .github/debug to stop the wait now."
for i in $(seq 1 60) 
do if test -e .github/debug 
   then sleep 60 ; echo "$i minutes elapsed"
   fi
done
