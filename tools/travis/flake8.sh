#!/bin/bash

PYTHON_FILES=~ \
             ~/tools/admin/wskadmin \
             ~/tools/build/citool \
             ~/tools/build/redo \
             ~/tools/health/isAlive \
             ~/tools/health/killComponent \
             ~/tools/health/kvstore

# First round uses --exit-zero to treat every message as a warning
flake8 $PYTHON_FILES --count --max-line-length=127 --statistics --exit-zero
# Second round stops the build if there are any syntax errors
flake8 PYTHON_FILES --count --max-line-length=127 --select=E999 --statistics
