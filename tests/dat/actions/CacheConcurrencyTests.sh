#!/usr/bin/env bash

N=20
WSK="${1-./bin/wsk} -i"

echo -n "init "
(for i in `seq 1 $N`; do ($WSK action delete testy$i &); done; wait) 2>&1 | grep -v failure > /dev/null
$WSK action list -l 200 | grep -v testy > /dev/null
if [ $? == 1 ]; then
    echo "FAIL"
    exit 1
else
    echo "PASS"
fi

echo -n "create "
(for i in `seq 1 $N`; do ($WSK action create testy$i ~/foo.js &); done; wait) 2>&1 | grep -v failure > /dev/null

if [ $? == 1 ]; then
    echo "FAIL"
    exit 1
else
    echo "PASS"
fi

echo -n "update "
(for i in `seq 1 $N`; do ($WSK action update testy$i -p p v &); done; wait) 2>&1 | grep -v failure > /dev/null

if [ $? == 1 ]; then
    echo "FAIL in update"
    exit 1
else
    echo "PASS"
fi

echo -n "delete+get "
(for i in `seq 1 $N`; do ($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action delete testy$i &); ($WSK action get testy$i &); ($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &); done; wait) 2>&1 | grep -v "unable to delete" > /dev/null

if [ $? == 1 ]; then
    echo "FAIL"
    exit 1
else
    echo "PASS"
fi

echo -n "get after delete "
(for i in `seq 1 $N`; do ($WSK action get testy$i &); done; wait) 2>&1 | grep ok > /dev/null

if [ $? == 0 ]; then
    echo "FAIL"
    exit 1
else
    echo "PASS"
fi


echo -n "create "
(for i in `seq 1 $N`; do ($WSK action create testy$i ~/foo.js &); done; wait) 2>&1 | grep -v failure > /dev/null

if [ $? == 1 ]; then
    echo "FAIL"
    exit 1
else
    echo "PASS"
fi

for i in `seq 1 $N`; do $WSK action update testy$i -p smurf zoomba >& /dev/null; done

echo -n "update+get "
(for i in `seq 1 $N`; do ($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action update testy$i -p smurf blue &); ($WSK action get testy$i &); ($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &);($WSK action get testy$i &); done; wait) 2>&1 | grep "ok: updated action" > /dev/null

if [ $? == 1 ]; then
    echo "FAIL"
    exit 1
else
    echo "PASS"
fi

echo -n "get after update+get "
for i in `seq 1 $N`; do
    $WSK action get testy$i | grep zoomba > /dev/null
    if [ $? == 0 ]; then
	echo "FAIL"
	exit 1
    fi
done
echo "PASS"

