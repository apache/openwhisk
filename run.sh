#!/bin/bash
# Basic while loop
counter=1
while [ $counter -le 30 ]
do
wsk action invoke hi
counter=`expr $counter + 1`
done
