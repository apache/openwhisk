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
import argparse
import collections
try:
    import configparser
except ImportError:
    import ConfigParser as configparser
import fnmatch
import itertools
import os
import platform
import re
import sys
import textwrap

VERBOSE = False

# Terminal colors
BLUE = '\033[94m'
CYAN = '\033[36m'
GREEN = '\033[92m'
RED = '\033[91m'
YELLOW = '\033[33m'

# Translatable messages (error and general)
ERR_GENERAL = "an unspecified error was detected."
ERR_INVALID_CONFIG_FILE = "Invalid configuration file [%s]: %s.\n"
ERR_LICENSE = "file does not include required license header."
ERR_NO_EOL_AT_EOF = "file does not end with EOL."
ERR_PATH_IS_NOT_DIRECTORY = "%s: [%s] is not a valid directory.\n"
ERR_REQUIRED_SECTION = "Configuration file missing required section: [%s]"
ERR_SYMBOLIC_LINK = "file is a symbolic link."
ERR_TABS = "line contains tabs."
ERR_TRAILING_WHITESPACE = "line has trailing whitespaces."
ERR_LICENSE_FILE_NOT_FOUND = "License file [%s] could not be found."
ERR_INVALID_SCAN_FUNCTION = "Config. file filter [%s] lists invalid " \
                            "function [%s]."
HELP_CONFIG_FILE = "provide custom configuration file"
HELP_DISPLAY_EXCLUSIONS = "display path exclusion information"
HELP_ROOT_DIR = "starting directory for the scan"
HELP_VERBOSE = "enable verbose output"
MSG_CHECKING_FILE = "  [%s]..."
MSG_CHECKS_PASSED = "All checks passed."
MSG_CONFIG_ADDING_LICENSE_FILE = "Adding valid license from: [%s], value:\n%s"
MSG_ERROR_SUMMARY = "Scan detected %d error(s) in %d file(s):"
MSG_READING_CONFIGURATION = "Reading configuration file [%s]..."
MSG_READING_LICENSE_FILE = "Reading license file [%s]..."
MSG_RUNNING_FILE_CHECKS = "    Running File Check [%s]"
MSG_RUNNING_LINE_CHECKS = "    Running Line Check [%s]"
MSG_SCANNING_FILTER = "Scanning files with filter: [%s]:"
MSG_SCANNING_STARTED = "Scanning files starting at [%s]..."
WARN_CONFIG_SECTION_NOT_FOUND = "Configuration file section [%s] not found."
WARN_SCAN_EXCLUDED_PATH_SUMMARY = "Scan excluded (%s) directories:"
WARN_SCAN_EXCLUDED_FILE_SUMMARY = "Scan excluded (%s) files:"
WARN_SCAN_EXCLUDED_FILE = "  Excluded file: %s"
WARN_SCAN_EXCLUDED_PATH = "  Excluded path: %s"
MSG_DESCRIPTION = "Scans all source code under specified directory for " \
                  "project compliance using provided configuration."

# Default values for command line arguments
DEFAULT_ROOT_DIR = "."
DEFAULT_PROGRAM_PATH = "./"
DEFAULT_CONFIG_FILE = "scanCode.cfg"
DEFAULT_LICENSE_SEARCH_SLACK = 500

# Configuration file sections
SECTION_LICENSE = "Licenses"
SECTION_EXCLUDE = "Excludes"
SECTION_INCLUDE = "Includes"
SECTION_OPTIONS = "Options"

# Configuration Options known keys
OPT_LICENSE_SLACK_LEN = "license_slack_length"

# Globals
"""Hold valid license headers within an array strings."""
valid_licenses = []
exclusion_paths = []
exclusion_files_set = set()
license_search_slack_len = DEFAULT_LICENSE_SEARCH_SLACK
FILE_CHECK_FUNCTIONS = dict()
LINE_CHECK_FUNCTIONS = dict()
FILTERS_WITH_CHECK_FUNCTIONS = []


def print_error(msg):
    """Print error message to stderr."""
    sys.stderr.write(col.red(msg) + "\n")


def print_warning(msg):
    """Print warning message to stdout."""
    print(col.yellow(msg))


def print_status(msg):
    """Print status message to stdout."""
    print(msg)


def print_success(msg):
    """Print success message to stdout."""
    print(col.green(msg))


def print_highlight(msg):
    """Print highlighted message to stdout."""
    print(col.cyan(msg))


def vprint(s):
    """Conditional print (stdout)."""
    if VERBOSE:
        print_status(s)


def get_config_section_dict(config, section):
    """Retrieve key-value(s) for requested section of a config. file."""
    dict1 = {}
    try:
        options = config.options(section)
        # print_warning("options for section: %s\n%s" % (section, options))
        for option in options:
            try:
                dict1[option] = config.get(section, option)
            except:
                dict1[option] = None
    except:
        print_warning(WARN_CONFIG_SECTION_NOT_FOUND % section)
        return None
    return dict1


def find_license_on_path(filename, path):
    """Find the specified filename in path; return it or raise error."""
    filename = os.path.join(path, filename)

    if not os.path.exists(filename):
        raise Exception(ERR_LICENSE_FILE_NOT_FOUND %
                        filename)
    return filename


def read_license_files(config):
    """Read the license files to use when scanning source files."""
    file_dict = get_config_section_dict(config, SECTION_LICENSE)
    # vprint("file_dict: " + str(file_dict))
    if file_dict is not None:
        # for each key (license filename) in license section
        for license_filename in file_dict:
            # Read and append text of each license (header) to a global array.
            # Each 'key' should be a filename containing license text.
            try:

                # if the file is not in current directory, try to find
                # it in the path this script is being executed from.
                if not os.path.exists(license_filename):
                    license_filename = find_license_on_path(
                        license_filename,
                        DEFAULT_PROGRAM_PATH)

                with open(license_filename, 'r') as temp_file:
                    vprint(MSG_READING_LICENSE_FILE % license_filename)
                    str1 = str(temp_file.read())
                    valid_licenses.append(str(str1))
                    vprint(MSG_CONFIG_ADDING_LICENSE_FILE % (license_filename,
                                                             str1))
            except Exception as e:
                raise e
    else:
        raise Exception(ERR_REQUIRED_SECTION % SECTION_LICENSE)


def read_path_exclusions(config):
    """Read the list of paths to exclude from the scan."""
    path_dict = get_config_section_dict(config, SECTION_EXCLUDE)
    # vprint("path_dict: " + str(path_dict))
    if path_dict is not None:
        # each 'key' is an exclusion path
        for key in path_dict:
            if key is not None:
                exclusion_paths.append(key)
    else:
        raise Exception(ERR_REQUIRED_SECTION % SECTION_LICENSE)


def read_scan_options(config):
    """Read the Options from the configuration file."""
    options_dict = get_config_section_dict(config, SECTION_OPTIONS)
    # vprint("options_dict: " + str(options_dict))
    if options_dict is not None:
        # Check for license scan slack length option
        # Set global variable to value found in config.
        if OPT_LICENSE_SLACK_LEN in options_dict:
            global license_search_slack_len
            license_search_slack_len = int(options_dict[OPT_LICENSE_SLACK_LEN])
    else:
        raise Exception(ERR_REQUIRED_SECTION % SECTION_OPTIONS)


def read_config_file(file):
    """Read in and validate configuration file."""
    try:
        print_highlight(MSG_READING_CONFIGURATION % file.name)
        # Provide for sections that have simply values (not key=value)
        config = configparser.ConfigParser(allow_no_value=True)
        # This option prevents options from being normalized to lowercase
        # by allowing the raw string in the config. to be passed through
        config.optionxform = str
        config.readfp(file)
        read_license_files(config)
        read_path_inclusions(config)
        read_path_exclusions(config)
        read_scan_options(config)
    except Exception as e:
        print_error(e)
        return -1
    return 0


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


def has_block_license(path):
    """Open file and verify it contains a valid license header."""
    with open(path) as fp:
        for license in valid_licenses:
            # Assure license string is normalized to remove indentations
            # caused by declaration (above) as a string literal.
            normalized_license = textwrap.dedent(license)
            # Search for license at start of file,
            # allowing for some "slack" length
            file_head = fp.read(len(normalized_license) +
                                license_search_slack_len)

            if file_head is None:
                return [(1, ERR_LICENSE)]
            elif normalized_license in file_head:
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


# Note: this function must appear after all "check" functions are defined
def read_path_inclusions(config):
    """Read the list of paths to include in scan tests."""
    inclusion_dict = get_config_section_dict(config, SECTION_INCLUDE)
    # vprint("inclusion_dict: " + str(inclusion_dict))

    for key in inclusion_dict:
        all_checks = inclusion_dict[key]
        # strip off all whitespace, regardless of index
        all_checks = all_checks.replace(' ', '')
        # retrieve the names of all functions to scan for
        # the respective filename (wildcards allowed)
        function_names = all_checks.split(',')
        file_check_fxs = []
        line_check_fxs = []
        for fname in function_names:
            try:
                fx = globals()[fname]
                if fname in FILE_CHECK_FUNCTIONS:
                    file_check_fxs.append(fx)
                elif fname in LINE_CHECK_FUNCTIONS:
                    line_check_fxs.append(fx)
            except Exception:
                print_error(ERR_INVALID_SCAN_FUNCTION % (key, fname))
                sys.exit(1)

        a_tuple = (key, file_check_fxs, line_check_fxs)
        FILTERS_WITH_CHECK_FUNCTIONS.append(a_tuple)
    # vprint("filters(checks):" + str(FILTERS_WITH_CHECK_FUNCTIONS))


def run_file_checks(file_path, checks):
    """Run a series of file-by-file checks."""
    errors = []
    # if VERBOSE (True) then print filename being checked
    vprint(MSG_CHECKING_FILE % file_path)
    for check in checks:
        vprint(col.cyan(MSG_RUNNING_FILE_CHECKS % check.__name__))
        errs = check(file_path)
        if errs:
            errors += errs
    return errors


def run_line_checks(file_path, checks):
    """."""
    errors = []
    line_number = 0
    # For each line in the file, run all "line checks"
    with open(file_path) as fp:
        for line in fp:
            line_number += 1
            for check in checks:
                if line_number == 1:
                    vprint(col.cyan(MSG_RUNNING_LINE_CHECKS %
                                    check.__name__))
                err = check(line)
                if err is not None:
                    errors.append((line_number, err))
    return errors


def all_paths(root_dir):
    """Generator that returns files with known extensions that can be scanned.

    Iteration is recursive beginning at the passed root directory and
    skipping directories that are listed as exception paths.
    """
    # For every file in every directory (path) starting at "root_dir"
    for dir_path, dir_names, files in os.walk(root_dir):
        for f in files:
            # Map will contain a boolean for each exclusion path tested
            # as input to the lambda function.
            # only if all() values in the Map are "True" (meaning the file is
            # not excluded) then it should yield the filename to run checks on.
            # not dir_path.endswith(p) and
            if all(map(lambda p: p not in dir_path,
                       exclusion_paths)):
                yield os.path.join(dir_path, f)
            else:
                exclusion_files_set.add(os.path.join(dir_path, f))


def colors():
    """Create a collection of helper functions to colorize strings."""
    ansi = hasattr(sys.stderr, "isatty") and platform.system() != "Windows"

    def colorize(code, string):
        # Enable ANSI terminal color only around string provided (if valid)
        return "%s%s%s" % (code, string, '\033[0m') if ansi else string

    def cyan(s):
        return colorize(CYAN, s)

    def green(s):
        return colorize(GREEN, s)

    def red(s):
        return colorize(RED, s)

    def yellow(s):
        return colorize(YELLOW, s)

    return collections.namedtuple(
        "Colorizer",
        "cyan green red yellow")(cyan, green, red, yellow)

# Script entrypoint.
if __name__ == "__main__":

    # Prepare message colorization methods
    col = colors()

    # Parser helpers
    def is_dir(path):
        """Check if path is a directory."""
        return os.path.isdir(root_dir)

    # identify the path (directory) where scanCode.py is located
    # Use this as default for finding default configuration
    DEFAULT_PROGRAM_PATH = os.path.split(os.path.abspath(__file__))[0]
    # vprintf("DEFAULT_PROGRAM_PATH: =[%s]" % DEFAULT_PROGRAM_PATH)
    DEFAULT_CONFIG_FILE = os.path.join(DEFAULT_PROGRAM_PATH,
                                       DEFAULT_CONFIG_FILE)

    # create / configure our argument parser
    # Note: ArgumentParser catches all errors and outputs a message
    # to override this behavior you would need to subclass it.
    parser = argparse.ArgumentParser(description=MSG_DESCRIPTION)
    parser.add_argument("-v", "--verbose",
                        action="store_true",
                        dest="verbose",
                        default=False,
                        help=HELP_VERBOSE)
    parser.add_argument("-x",
                        action="store_true",
                        dest="display_exclusions",
                        default=False,
                        help=HELP_DISPLAY_EXCLUSIONS)
    parser.add_argument("--config",
                        type=argparse.FileType('r'),
                        action="store",
                        dest="config",
                        default=DEFAULT_CONFIG_FILE,
                        help=HELP_CONFIG_FILE)
    parser.add_argument("root_directory",
                        type=str,
                        default=DEFAULT_ROOT_DIR,
                        help=HELP_ROOT_DIR)

    # Invoke parser, assign argument values to locals
    args = parser.parse_args()
    root_dir = args.root_directory
    VERBOSE = args.verbose

    # Config file at this point is an actual file object
    config_file = args.config

    # Assign supported scan functions to either file or line globals
    # These checks run once per-file
    FILE_CHECK_FUNCTIONS.update({
        "is_not_symlink": is_not_symlink,
        "has_block_license": has_block_license
    })

    # These checks run once per-line, per-file
    LINE_CHECK_FUNCTIONS.update({
        "no_tabs": no_tabs,
        "no_trailing_spaces": no_trailing_spaces,
        "eol_at_eof": eol_at_eof
    })

    # Read / load configuration file from file (pointer)
    if read_config_file(config_file) == -1:
        exit(1)

    # Verify starting path parameter is valid
    if not is_dir(root_dir):
        print_error(ERR_PATH_IS_NOT_DIRECTORY % (sys.argv[0], root_dir))
        parser.print_help()
        exit(1)

    # Positive feedback to caller that scanning has started
    print_highlight(MSG_SCANNING_STARTED % root_dir)

    # Runs all listed checks on all relevant files.
    all_errors = []

    for fltr, chks1, chks2 in FILTERS_WITH_CHECK_FUNCTIONS:
        # print_error(col.cyan(MSG_SCANNING_FILTER % fltr))
        # print_error("chks1=" + str(chks1))
        # print_error("chks2=" + str(chks2))
        for path in fnmatch.filter(all_paths(root_dir), fltr):
            errors = run_file_checks(path, chks1)
            errors += run_line_checks(path, chks2)
            all_errors += map(lambda p: (path, p[0], p[1]), errors)

    # Display path and file exclusion details
    if args.display_exclusions or VERBOSE:
        print_warning(WARN_SCAN_EXCLUDED_PATH_SUMMARY % len(exclusion_paths))
        # Display all paths that were excluded (by configuration)
        for excluded_path in exclusion_paths:
            print_warning(WARN_SCAN_EXCLUDED_PATH % excluded_path)

        # Inform caller which files where excluded from these paths
        print_warning(WARN_SCAN_EXCLUDED_FILE_SUMMARY %
                      len(exclusion_files_set))
        for excluded_file in exclusion_files_set:
            print_warning(WARN_SCAN_EXCLUDED_FILE % excluded_file)

    def sort_key(p):
        """Define sort key for error listing as the filename."""
        # Filename is the 0th entry in tuple
        return p[0]

    if all_errors:
        # Group / sort errors by filename
        error_listing = ""
        files_with_errors = 0
        for path, triples in itertools.groupby(sorted(all_errors,
                                                      key=sort_key),
                                               key=sort_key):
            files_with_errors += 1
            error_listing += "  [%s]:\n" % path

            pairs = sorted(map(lambda t: (t[1], t[2]), triples),
                           key=lambda p: p[0])
            for line, msg in pairs:
                error_listing += col.red("    %4d: %s\n" % (line, msg))

        # Summarize errors
        summary = MSG_ERROR_SUMMARY % (len(all_errors), files_with_errors)
        print_highlight(summary)
        print(error_listing)
        print_error(summary)
        sys.exit(1)
    else:
        print_success(MSG_CHECKS_PASSED)
sys.exit(0)
