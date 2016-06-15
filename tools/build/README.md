# Build helper scripts

This directory contains `scanCode.py`, which checks all code for conformance with respect to certain conventions, and the `redo` script, a wrapper around Ansible and Gradle commands, for which examples are given below.

## Usage information
`redo -h`

## Initialize environment and `docker-machine` (for mac)
`redo setup prereqs`

### Start CouchDB container and initialize DB with system and guest keys
`redo couchdb initdb`

### Deploy
`redo deploy`

...and optionally to run tests:
`redo props tests`

*Or* to do it all with one line for a first time run `redo setup prereqs couchdb initdb deploy tests` as each of these is executed sequentially.

The script is called `redo` because for most development, one will want to "redo" the compilation and deployment of a unit as in `redo controller` which will `gradle` build the `controller`, teardown down the previous container and replace it with a new container (aka "hotswapping").

  * To only build: `redo controller -b`.
  * To only teardown: `redo controller -x`.
  * To redeploy only: `redo controller -d`.
  * To do all at once: `redo controller -bxd` which is the default.
