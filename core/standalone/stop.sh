#/bin/sh
ps -o comm,pid ax | awk '/^java/ { print $2 }' | xargs kill
