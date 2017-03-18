#!/bin/bash

pip3 install --user --upgrade flake8
flake8 --version

# These files do not have a .py extension so flake8 will not scan them
PYTHON_FILES=. \
             ./tools/admin/wskadmin \
             ./tools/build/citool \
             ./tools/build/redo \
             ./tools/health/isAlive \
             ./tools/health/killComponent \
             ./tools/health/kvstore

# First round uses --exit-zero to treat _every_ message as a warning
flake8 $PYTHON_FILES --count --max-line-length=127 --statistics --exit-zero
# Second round stops the build if there are any syntax errors
flake8 PYTHON_FILES --count --max-line-length=127 --select=E999 --statistics
