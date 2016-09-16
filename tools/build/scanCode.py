#!/usr/bin/env python

#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import collections
import fnmatch
import itertools
import os
import platform
import re
import sys
import textwrap

# This script checks some (simple) conventions:
#   - no symlinks
#   - no tabs
#   - no trailing whitespaces
#   - files end with EOL
#   - license headers everywhere applicable

####
# Exceptions: these paths are not subjected to the tests for one reason or another.
####
def exceptional_paths():
    return [
        "bin/wskadmin",
        "bin/wskdev",
        "tests/build/reports"
    ]

####
# Line checks. Check a file line-by-line and produce at most one error per line.
####
def no_tabs(line):
    if re.match("\t", line):
        return "line contains tabs"
    else:
        return None

def no_trailing_spaces(line):
    if len(line) > 0 and line[-1] == '\n':
        line = line[:-1]

    if re.match("""^.*\s$""", line):
        return "line has trailing whitespaces"
    else:
        return None

def eol_at_eof(line):
    if len(line) == 0 or line[-1] != '\n':
        return "file does not end with EOL"
    else:
        return None

####
# File checks. Check a file in its entirety and produce an arbitrary number of errors.
####
def has_block_copyright(path):
    header = textwrap.dedent("""\
        /*
         * Copyright 2015-2016 IBM Corporation
         *
         * Licensed under the Apache License, Version 2.0 (the "License");
         * you may not use this file except in compliance with the License.
         * You may obtain a copy of the License at
         *
         * http://www.apache.org/licenses/LICENSE-2.0
         *
         * Unless required by applicable law or agreed to in writing, software
         * distributed under the License is distributed on an "AS IS" BASIS,
         * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
         * See the License for the specific language governing permissions and
         * limitations under the License.
         */
        """)

    with open(path) as fp:
        file_head = fp.read(len(header))

    if file_head is None or file_head != header:
        return [ (1, "file does not include standard copyright header" ) ]
    else:
        return [ ]

def is_not_symlink(path):
    if os.path.islink(path):
        return [ (0, "file is a symbolic link") ]
    else:
        return None

# Turns a series of line-by-line checks into a single file-by-file check.
def line_checks(checks):
    def run_line_checks(file_path):
        errors = []
        ln = 0
        with open(file_path) as fp:
            for line in fp:
                ln += 1
                for check in checks:
                    err = check(line)
                    if err is not None:
                        errors.append((ln, err))
        return errors
    return run_line_checks

# Runs a series of file-by-file checks.
def run_file_checks(file_path, checks):
    errors = []

    for check in checks:
        errs = check(file_path)
        if errs:
            errors += errs

    return errors

# Like `find`, minus the directories.
def all_paths(root_dir):
    for dir_path, dir_names, files in os.walk(root_dir):
        for f in files:
            path = os.path.join(dir_path, f)
            if all(map(lambda p: not path.endswith(p), exceptional_paths())):
                yield os.path.join(dir_path, f)

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
    if len(sys.argv) != 2:
        sys.stderr.write("Usage: %s root_directory.\n" % sys.argv[0])
        sys.exit(1)

    root_dir = sys.argv[1]

    col = colors()

    if not os.path.isdir(root_dir):
        sys.stderr.write("%s: %s is not a directory.\n" % (sys.argv[0], root_dir))

    # This determines which checks run on which files.
    file_checks = [
        ("*",          [ is_not_symlink ]),
        ("*.scala",    [ has_block_copyright, line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ]),
        ("*.py",       [ line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ]),
        ("*.java",     [ has_block_copyright, line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ]),
        ("*.js",       [ line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ]),
        ("build.xml",  [ line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ]),
        ("deploy.xml", [ line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ]),
        ("*.gradle",   [ line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ]),
        ("*.md",       [ line_checks([ no_tabs, eol_at_eof ]) ]),
        ("*.go",       [ has_block_copyright, line_checks([ no_tabs, no_trailing_spaces, eol_at_eof ]) ])
    ]

    all_errors = []

    # Runs all relevant checks on all relevant files.
    for fltr, checks in file_checks:
        for path in fnmatch.filter(all_paths(root_dir), fltr):
            errors = run_file_checks(path, checks)
            all_errors += map(lambda p: (path, p[0], p[1]), errors)

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
        print col.green("All checks passed.")
        sys.exit(0)
