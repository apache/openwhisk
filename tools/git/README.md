# Purpose

This directory contains shell scripts to be used in conjunction with `git` operations.

## Pre-commit hooks

This directory contains following `pre-commit` hooks. Read `man githooks` for details
about `pre-commit` hooks and how to install / use them.

### Scala source formatting

Any of the following `pre-commit` hooks can be used to format all staged Scala source files (`*.scala`)
according to project standards. Said files are changed in-place and re-staged so that committed
files are properly formatted.

* `pre-commit-scalafmt-gradlew.sh`: Use Gradle wrapper for formatting.
* `pre-commit-scalafmt-native.sh`: Use `scalafmt` command for formatting. Less overhead and thus,
  faster than Gradle wrapper approach. You have to install `scalafmt` command - see http://scalameta.org/scalafmt/.
