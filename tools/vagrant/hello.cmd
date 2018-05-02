@REM Licensed to the Apache Software Foundation (ASF) under one or more contributor
@REM license agreements; and to You under the Apache License, Version 2.0.

@REM Start with a new VM
IF NOT EXIST .vagrant\ GOTO SKIPDESTROY
vagrant destroy
:SKIPDESTROY

vagrant plugin install vagrant-disksize
vagrant up
