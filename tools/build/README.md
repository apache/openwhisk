# Build helper scripts

This directory contains the following utilities.
- `scanCode.py`: checks all code for conformance with respect to certain conventions.
   - Please note that this utility has been moved to the incubator-openwhisk-utilities repository so that all Apache OpenWhisk repositories may more easily reference it. This version will be removed once all other repositories in the project correctly reference it in its new location.
- `redo`: a wrapper around Ansible and Gradle commands, for which examples are given below,
- `citool`: allows for command line monitoring of Jenkins and Travis CI builds.

## How to use `redo`

The script is called `redo` because for most development, one will want to "redo" the compilation and deployment.

- usage information: `redo -h`
- initialize environment and `docker-machine` (for mac): `redo setup prereq`
- start CouchDB container and initialize DB with system and guest keys: `redo couchdb initdb`
- build and deploy system: `redo deploy`
- run tests: `redo props tests`

To do a fresh build and deploy all with one line for a first time run `redo setup prereq couchdb initdb deploy tests` as each of these is executed sequentially.

Individual components such as the `controller` may be rebuilt and redeployed as well.

  * To only build: `redo controller -b`.
  * To only teardown: `redo controller -x`.
  * To redeploy only: `redo controller -d`.
  * To do all at once: `redo controller -bxd` which is the default.

Additional arguments may be passed to underlying shell commands for Gradle and Ansible using `-a`.
For example, the following is handy to run a subset of all tests from the command line.

  * `redo tests -a '--tests package.name.TestClass.evenMethodName'`

## How to use `citool`

This script allows for monitoring of ongoing Jenkins and Travis builds.
The script assumes by default that the monitored job is a Travis CI build hosted here `https://api.travis-ci.org/`.
To change the Travis (or Jenkins) host URL, use `-u`.

- usage information: `citool -h`
- monitor a Travis CI build with job number `N`: `citool monitor N`
- monitor same job `N` until completion: `citool monitor -p N`
- save job output to a file: `citool -o monitor N`

To monitor a Jenkins build `B` with job number `N` on host `https://jenkins.host:port`:
```
citool -u https://jenkins.host:port -b B monitor N
```

The script also allows for gathering controller and invoker log artifacts from a Jenkins build job. For example,
to retrieve logs for a deployment with 1 controller and 1 invoker for build `B` with job number `N` on
host `https://jenkins.host:port` with the artifacts are stored in `whisk/logs` relative to the job URL:

```
citool -u https://jenkins.host:port -b B cat whisk/logs N
```

It is sometimes convenient to save the logs locally (via `citool -o ...`) to avoid fetching them repeatedly if one wishes
to inspect the logs and extract a specific transaction. Logs statements may be sorted according to their timestamps using `cat -s`.
Additionally to grep for a specific expression, use `cat -g`.

```
citool -o -u https://jenkins.host:port -b B cat -s -g "tid_123" whisk/logs N
```

The logs are saved to `./B-build.log` and can be reprocessed using `citool` with `-i`.

```
citool -i -b B cat -s -g "tid_124" whisk/logs N
```

## Troubleshooting

If you encounter an error `ImportError: No module named pkg_resources` while running `redo`, try the workaround below
or see [these instructions](https://pypi.python.org/pypi/setuptools/0.9.8#installation-instructions) for upgrading `setuptools`.

```
pip install --upgrade setuptools
```
