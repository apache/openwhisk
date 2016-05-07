@REM Start with a new VM
IF NOT EXIST .vagrant\ GOTO SKIPDESTROY
vagrant destroy
:SKIPDESTROY

vagrant up

wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
if errorlevel 1 (
   echo wsk failed, build might be broken try running .\resume_build
   exit /b %errorlevel%
)
