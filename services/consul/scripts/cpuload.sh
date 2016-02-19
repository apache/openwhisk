#!/bin/bash


# Exit code 0 - Check is passing
# Exit code 1 - Check is warning
# Any other code - Check is failing


CPU_Util=$(uptime | grep "load" | awk '{printf "%.2f", $(NF-2)}')
echo $CPU_Util
if [ $(echo " $CPU_Util > 0.90" | bc) -eq 1 ]
   then echo 'load 90%' && exit 2
elif [ $(echo " $CPU_Util > 0.80" | bc) -eq 1 ]
   then echo 'load 80%' && exit 1
else echo 'load below 80%' && exit 0; 
fi
