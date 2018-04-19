@REM Start with a new VM
IF NOT EXIST .vagrant\ GOTO SKIPDESTROY
vagrant destroy
:SKIPDESTROY

vagrant plugin install vagrant-disksize
vagrant up
