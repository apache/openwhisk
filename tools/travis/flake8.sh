#!/bin/bash

PYTHON_FILES=~/openwhisk \
             ~/openwhisk/openwhisk/tools/admin/wskadmin \
             ~/openwhisk/openwhisk/tools/build/citool \
             ~/openwhisk/openwhisk/tools/build/redo \
             ~/openwhisk/openwhisk/tools/health/isAlive \
             ~/openwhisk/openwhisk/tools/health/killComponent \
             ~/openwhisk/openwhisk/tools/health/kvstore

# First round uses --exit-zero to treat every message as a warning
flake8 $PYTHON_FILES --count --max-line-length=127 --statistics --exit-zero
# Second round stops the build if there are any syntax errors
flake8 PYTHON_FILES --count --max-line-length=127 --select=E999 --statistics
