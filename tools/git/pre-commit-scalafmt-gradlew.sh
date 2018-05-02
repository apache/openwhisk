#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Purpose: Run this script as a git pre-commit hook to apply project-specific
#          Scala formatting rules to all staged Scala source files (*.scala).
#          Uses Gradle wrapper to perform Scala formatting with `scalafmt`.
#          The script will re-stage the formatted Scala source files.

# Uncomment the following line to obtain Shell script execution tracing.
# set -x

# -u: fail if variable is undefined
# -f: disable globbing = file name expansion with regular expressions
# -e: fail on non-zero exit code
set -u -f -e

# Determine OpenWhisk base directory
ROOT_DIR="$(git rev-parse --show-toplevel)"

# Run `scalafmt` iff there are staged .scala source files
set +e
STAGED_SCALA_FILES=$(git diff --cached --name-only --no-color --diff-filter=d --exit-code -- "${ROOT_DIR}/*.scala")
STAGED_SCALA_FILES_DETECTED=$?
set -e

if [ "${STAGED_SCALA_FILES_DETECTED}" -eq 1 ]; then
    # Re-format scala code iff a scala file is staged
    "${ROOT_DIR}/gradlew" --project-dir "${ROOT_DIR}" scalafmtAll

    # Re-add all staged .scala files
    for SCALA_FILE in ${STAGED_SCALA_FILES}
    do
      git add -- "${ROOT_DIR}/${SCALA_FILE}"
    done
fi

exit 0
