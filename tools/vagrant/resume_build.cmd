vagrant ssh -- "cd openwhisk && ant build" || exit /b

vagrant ssh -- "cd openwhisk && ant deploy" || exit /b

vagrant ssh -- "cd openwhisk && source tools/ubuntu-setup/bashprofile.sh" || exit /b

vagrant ssh -- "source .bash_profile" || exit /b

vagrant ssh -- "cd openwhisk && cat config/keys/auth.guest | xargs bin/wsk property set --auth" || exit /b

vagrant ssh -- "openwhisk/bin/wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result" || exit /b
