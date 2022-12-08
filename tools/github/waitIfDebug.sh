#!/bin/bash
echo "You have an hour to debug this build."
echo "remove .github/debug to stop the wait now."
for i in $(seq 1 60) 
do if test -e .github/debug 
      sleep 60
      echo "ngrok active since $i minutes"
   fi
done
