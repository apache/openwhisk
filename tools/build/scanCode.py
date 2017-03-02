#!/usr/bin/env python
"""Executable Python script for scanning source code for compliance.

   This script checks some (simple) conventions:
   - no symlinks
   - no tabs
   - no trailing whitespace
   - files end with EOL
   - valid license headers in source files (where applicable)

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

import collections
import fnmatch
import itertools
import os
import platform
import re
import sys
import textwrap

VERBOSE = False

# Translatable messages (error and general)
ERR_LICENSE = "file does not include required license header."
ERR_SYMBOLIC_LINK = "file is a symbolic link."
ERR_TABS = "line contains tabs."
ERR_TRAILING_WHITESPACE = "line has trailing whitespaces."
ERR_NO_EOL_AT_EOF = "file does not end with EOL."
ERR_PATH_IS_NOT_DIRECTORY = "%s: %s is not a directory.\n"
ERR_GENERAL = "There was an error."
MSG_CHECKING_FILE = "Checking file[%s]..."
MSG_CHECKS_PASSED = "All checks passed."
MSG_SCRIPT_USAGE = "Usage: %s root_directory\n"
MSG_ERROR_SUMMARY = "Summary: Scan detected %d error(s) in %d file(s)."


def vprint(s):
    """Conditional print (stdout)."""
    if VERBOSE:
        print s


def exceptional_paths():
    """List of paths not subjected to the scan tests."""
    return [
        "bin/wskadmin",
        "bin/wskdev",
        "tests/build/reports"
    ]


def no_tabs(line):
    """Assert line does not contains a TAB character."""
    if re.match("\t", line):
        return ERR_TABS
    else:
        return None


def no_trailing_spaces(line):
    """Assert line does not have trailing whitespace."""
    if len(line) > 0 and line[-1] == '\n':
        line = line[:-1]

    if re.match("""^.*\s$""", line):
        return ERR_TRAILING_WHITESPACE
    else:
        return None


def eol_at_eof(line):
    """Assert line at End of File is an End of Line character."""
    if len(line) == 0 or line[-1] != '\n':
        return ERR_NO_EOL_AT_EOF
    else:
        return None

"""Declare approved software license headers as strings."""

LICENSE_APACHE_SOFTWARE_FOUNDATION = """\
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
LICENSE_IBM_APACHE = """\
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
"""


def has_block_license(path):
    """Open file and verify it contains a valid license header."""
    valid_licenses = [LICENSE_APACHE_SOFTWARE_FOUNDATION,
                      LICENSE_IBM_APACHE]

    with open(path) as fp:
        for license in valid_licenses:
            # Assure license string is normalized to remove indentations
            # caused by declaration (above) as a string literal.
            normalized_license = textwrap.dedent(license)

            file_head = fp.read(len(normalized_license))

            if file_head is None:
                return [(1, ERR_LICENSE)]
            elif file_head == normalized_license:
                return []
            # reset and try finding the next license
            fp.seek(0)
    return [(1, ERR_LICENSE)]


def is_not_symlink(path):
    """Assert a file is not a symbolic link."""
    if os.path.islink(path):
        return [(0, ERR_SYMBOLIC_LINK)]
    else:
        return None


def line_checks(checks):
    """Turn file-based check into line-by-line checks on each file."""
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


def run_file_checks(file_path, checks):
    """Run a series of file-by-file checks."""
    errors = []
    # if VERBOSE (True) then print filename being checked
    vprint(col.green(MSG_CHECKING_FILE % file_path))
    for check in checks:
        errs = check(file_path)
        if errs:
            errors += errs

    return errors


def all_paths(root_dir):
    """Generator that returns files with known extensions that can be scanned.

    Iteration is recursive beginning at the passed root directory and
    skipping directories that are listed as exception paths.
    """
    for dir_path, dir_names, files in os.walk(root_dir):
        for f in files:
            path = os.path.join(dir_path, f)
            if all(map(lambda p: not path.endswith(p), exceptional_paths())):
                yield os.path.join(dir_path, f)


def colors():
    """Create a collection of helper functions to colorize strings."""
    ansi = hasattr(sys.stderr, "isatty") and platform.system() != "Windows"

    def colorize(code, string):
        return "%s%s%s" % (code, string, '\033[0m') if ansi else string

    def blue(s):
        return colorize('\033[94m', s)

    def green(s):
        return colorize('\033[92m', s)

    def red(s):
        return colorize('\033[91m', s)

    return collections.namedtuple("Colorizer",
                                  "blue green red")(blue, green, red)

# Script entrypoint.
if __name__ == "__main__":

    # Prepare message colorization methods
    col = colors()

    # Test necessary arguments exist
    if len(sys.argv) < 2:
        sys.stderr.write(col.red(MSG_SCRIPT_USAGE % sys.argv[0]))
        sys.exit(1)

    root_dir = sys.argv[1]

    if not os.path.isdir(root_dir):
        sys.stderr.write(ERR_PATH_IS_NOT_DIRECTORY %
                         (sys.argv[0], root_dir))

    # This determines which checks run on which files.
    file_checks = [
        ("*", [is_not_symlink]),
        ("*.scala", [has_block_license,
                     line_checks([no_tabs,
                                  no_trailing_spaces,
                                  eol_at_eof])]),
        ("*.py", [line_checks([no_tabs,
                               no_trailing_spaces,
                               eol_at_eof])]),
        ("*.java", [has_block_license,
                    line_checks([
                        no_tabs,
                        no_trailing_spaces,
                        eol_at_eof])]),
        ("*.js", [line_checks([no_tabs,
                               no_trailing_spaces,
                               eol_at_eof])]),
        ("build.xml", [line_checks([no_tabs,
                                    no_trailing_spaces,
                                    eol_at_eof])]),
        ("deploy.xml", [line_checks([no_tabs, no_trailing_spaces,
                        eol_at_eof])]),
        ("*.gradle", [line_checks([no_tabs, no_trailing_spaces, eol_at_eof])]),
        ("*.md", [line_checks([no_tabs,
                               eol_at_eof])]),
        ("*.go", [has_block_license,
                  line_checks([no_tabs,
                               no_trailing_spaces,
                               eol_at_eof])])
    ]

    all_errors = []

    # Runs all listed checks on all relevant files.
    for fltr, checks in file_checks:
        for path in fnmatch.filter(all_paths(root_dir), fltr):
            errors = run_file_checks(path, checks)
            all_errors += map(lambda p: (path, p[0], p[1]), errors)

    def sort_key(p):
        """Define sort key for error listing as the filename."""
        # Filename is the 0th entry in tuple
        return p[0]

    # Group/sort errors by filename
    if all_errors:
        files_with_errors = 0

        for path, triples in itertools.groupby(sorted(all_errors,
                                                      key=sort_key),
                                               key=sort_key):
            files_with_errors += 1
            sys.stderr.write("%s:\n" % col.blue(path))

            pairs = sorted(map(lambda t: (t[1], t[2]), triples),
                           key=lambda p: p[0])
            for line, msg in pairs:
                sys.stderr.write("    %4d: %s\n" % (line, msg))

        # Summarize errors to stdout
        message = MSG_ERROR_SUMMARY % (len(all_errors), files_with_errors)
        sys.stderr.write(col.red(message) + "\n")
        sys.exit(1)
    else:
        print col.green(MSG_CHECKS_PASSED)
        sys.exit(0)
