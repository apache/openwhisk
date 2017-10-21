#!/bin/bash

pip3 install --user --upgrade flake8

# These files do not have a .py extension so flake8 will not scan them
declare -a PYTHON_FILES=("."
                         "./tools/admin/wskadmin"
                         "./tools/build/citool"
                         "./tools/build/redo")

echo 'Flake8: first round (fast fail) stops the build if there are any Python 3 syntax errors...'
for i in "${PYTHON_FILES[@]}"
do
    flake8 "$i" --select=E999,F821 --statistics
    RETURN_CODE=$?
    if [ $RETURN_CODE != 0 ]; then
        echo 'Flake8 found Python 3 syntax errors above.  See: https://docs.python.org/3/howto/pyporting.html'
        exit $RETURN_CODE
    fi
done

echo 'Flake8: second round uses the --exit-zero flag to treat _every_ message as a warning...'
for i in "${PYTHON_FILES[@]}"
do
    flake8 "$i" --ignore=E --max-line-length=127 --statistics --exit-zero
done
