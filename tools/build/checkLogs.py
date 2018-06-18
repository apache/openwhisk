#!/usr/bin/env python
"""Executable Python script for checking log (entries) sizes.

CI/CD tool to assert that logs and databases are in certain bounds.

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""

##
# CI/CD tool to assert that logs and databases are in certain bounds
##

import collections
import itertools
import os
import platform
import sys
import json
from functools import partial

def file_has_at_most_x_bytes(x, file):
    size = os.path.getsize(file)
    if(size > x):
        return [ (0, "file has %d bytes, expected %d bytes" % (size, x)) ]
    else:
        return [ ]

# Checks that the database dump contains at most x entries
def database_has_at_most_x_entries(x, file):
    with open(file) as db_file:
        data = json.load(db_file)
        entries = len(data['rows'])
        if(entries > x):
            return [ (0, "found %d database entries, expected %d entries" % (entries, x)) ]
        else:
            return [ ]

# Runs a series of file-by-file checks.
def run_file_checks(file_path, checks):
    errors = []

    for check in checks:
        errs = check(file_path)
        if errs:
            errors += errs

    return errors

# Helpers, rather than non-standard modules.
def colors():
    ansi = hasattr(sys.stderr, "isatty") and platform.system() != "Windows"

    def colorize(code, string):
        return "%s%s%s" % (code, string, '\033[0m') if ansi else string

    blue  = lambda s: colorize('\033[94m', s)
    green = lambda s: colorize('\033[92m', s)
    red   = lambda s: colorize('\033[91m', s)

    return collections.namedtuple("Colorizer", "blue green red")(blue, green, red)

# Script entrypoint.
if __name__ == "__main__":
    if len(sys.argv) > 3:
        sys.stderr.write("Usage: %s logs_directory.\n" % sys.argv[0])
        sys.exit(1)

    root_dir = sys.argv[1]

    tags_to_check = []
    if len(sys.argv) == 3:
        tags_to_check = {x.strip() for x in sys.argv[2].split(',')}

    col = colors()

    if not os.path.isdir(root_dir):
        sys.stderr.write("%s: %s is not a directory.\n" % (sys.argv[0], root_dir))

    file_checks = [
        ("db-rules.log", {"db"}, [ partial(database_has_at_most_x_entries, 0) ]),
        ("db-triggers.log", {"db"}, [ partial(database_has_at_most_x_entries, 0) ]),
        # Assert that stdout of the container is correctly piped and empty
        ("controller0.log", {"system"}, [ partial(file_has_at_most_x_bytes, 0) ]),
        ("invoker0.log", {"system"}, [ partial(file_has_at_most_x_bytes, 0) ])
    ]

    all_errors = []

    # Runs all relevant checks on all relevant files.
    for file_name, tags, checks in file_checks:
        if not tags_to_check or any(t in tags for t in tags_to_check):
            file_path = root_dir + "/" + file_name
            errors = run_file_checks(file_path, checks)
            all_errors += map(lambda p: (file_path, p[0], p[1]), errors)

    sort_key = lambda p: p[0]

    if all_errors:
        files_with_errors = 0
        for path, triples in itertools.groupby(sorted(all_errors, key=sort_key), key=sort_key):
            files_with_errors += 1
            sys.stderr.write("%s:\n" % col.blue(path))

            pairs = sorted(map(lambda t: (t[1],t[2]), triples), key=lambda p: p[0])
            for line, msg in pairs:
                sys.stderr.write("    %4d: %s\n" % (line, msg))

        # There's no reason not to pluralize properly.
        if len(all_errors) == 1:
            message = "There was an error."
        else:
            if files_with_errors == 1:
                message = "There were %d errors in a file." % len(all_errors)
            else:
                message = "There were %d errors in %d files." % (len(all_errors), files_with_errors)

        sys.stderr.write(col.red(message) + "\n")
        sys.exit(1)
    else:
        print(col.green("All checks passed."))
        sys.exit(0)
