#!/bin/bash
#
# Usage logdiff.sh <logfile>
# Makes two logfiles comparable by replacing all ever-changing data like activation ids, timestamps etc.
# Usually used in this way:
#
# 1. Run first test, e.g. ant runTests
# 2. Copy out logs using wskadmin db get whisks --docs --view whisks/activations > db-activations-first.log
# 3. Make some code changes
# 4. Re-deploy new code (clean, build, deploy)
# 5. Run second test, e.g. ant runTests
# 6. Copy out logs using wskadmin db get whisks --docs --view whisks/activations > db-activations-second.log
# 7. logdiff.sh db-activations-first.log > db-activations-first-normalized.log
# 8. logdiff.sh db-activations-second.log > db-activations-second-normalized.log
# 9. diff db-activations-first-normalized.log db-activations-second-normalized.log
#
# The resulting diff should only show relevant differences between two log files of two test runs.
#

LOGFILE=$1
: ${LOGFILE:?"LOGFILE must be set and non-empty"}

# replace uuids like activation ids
sed -e 's/[0-9A-Fa-f]\{32\}/<uuid>/g' $LOGFILE |
# replace msec timestamps
sed -e 's/[0-9]\{13,\}/<timestamp>/g' |
# replace UTC format timestamps
sed -e 's/[0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}T[0-9]\{2\}:[0-9]\{2\}:[0-9]\{2\}\.[0-9]\{1,\}Z/<UTC timestamp>/g' |
# replace javascript new Date().toString()-like timestamps
sed -E -e 's/(Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [0-9]+ [0-9]+:[0-9]+:[0-9]+ [A-Za-z]* [0-9]+/<English date string>/g'
