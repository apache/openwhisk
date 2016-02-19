#!/bin/sh

# Exit code 0 - Check is passing
# Exit code 1 - Check is warning
# Any other code - Check is failing

MEM_Util=$(free -m | awk 'NR==2 { printf "%.2f", $3/$2 }')
echo $MEM_Util
if [ $(echo " $MEM_Util > 0.90" | bc) -eq 1 ]
then echo 'load 90%' && exit 2;
   elif [ $(echo " $MEM_Util > 0.80" | bc) -eq 1 ]
then echo 'load 80%' && exit 1
   else echo 'load below 80%' && exit 0; 
fi

