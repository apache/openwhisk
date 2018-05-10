# Licensed to the Apache Software Foundation (ASF) under one or more contributor
# license agreements; and to You under the Apache License, Version 2.0.

#
# Python based OpenWhisk action that sleeps for the specified number
# of milliseconds before returning.
# The function actually sleeps slightly longer than requested.
#
# @param parm Object with Number property sleepTimeInMs
# @returns Object with String property msg describing how long the function slept
#
import time

def main(parm):
    sleepTimeInMs = parm.get("sleepTimeInMs", 1)
    print("Specified sleep time is {} ms.".format(sleepTimeInMs))

    result = { "msg": "Terminated successfully after around {} ms.".format(sleepTimeInMs) }

    time.sleep(sleepTimeInMs/1000.0)

    print(result['msg'])
    return result
