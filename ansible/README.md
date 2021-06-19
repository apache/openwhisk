<!--
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
-->
Deploying OpenWhisk using Ansible
=========


### Getting started

If you want to deploy OpenWhisk locally using Ansible, you first need to install Ansible on your development environment:

#### Ubuntu users
```shell script
sudo apt-get install python-pip
sudo pip install ansible==4.1.0
sudo pip install jinja2==3.0.1
```

#### Docker for Mac users
```shell script
sudo easy_install pip
sudo pip install ansible==4.1.0
pip install jinja2==3.0.1
```
Docker for Mac does not provide any official ways to meet some requirements for OpenWhisk.
You need to depend on the workarounds until Docker provides official methods.

If you prefer [Docker-machine](https://docs.docker.com/machine/) to [Docker for mac](https://docs.docker.com/docker-for-mac/), you can follow instructions in [docker-machine/README.md](../tools/macos/docker-machine/README.md).

##### Enable Docker remote API
The remote Docker API is required for collecting logs using the Ansible playbook [logs.yml](logs.yml).

##### Activate docker0 network (local dev only)
 
The OpenWhisk deployment via Ansible uses the `docker0` network interface to deploy OpenWhisk and it does not exist on Docker for Mac environment.

An expedient workaround is to add alias for `docker0` network to loopback interface.

```shell script
sudo ifconfig lo0 alias 172.17.0.1/24
```

### Using Ansible
**Caveat:** All Ansible commands are meant to be executed from the `ansible` directory.
This is important because that's where `ansible.cfg` is located which contains generic settings that are needed for the remaining steps.

Set the environment for the commands below by running
```shell script
ENVIRONMENT=local  # or docker-machine or jenkins or vagrant
```

The default environment is `local` which works for Ubuntu and
Docker for Mac. To use the default environment, you may omit the `-i` parameter entirely. For older Mac installation using Docker Machine,
use `-i environments/docker-machine`.

In all instructions, replace `<openwhisk_home>` with the base directory of your OpenWhisk source tree. e.g. `openwhisk`

#### Ansible with pyenv (local dev only)

When using [pyenv](https://github.com/pyenv/pyenv) to manage your versions of python, the [ansible python interpreter](https://docs.ansible.com/ansible/latest/reference_appendices/python_3_support.html) will use your system's default python, which may have a different version. 

To make sure ansible uses the same version of python which you configured, execute: 

```bash
echo -e "\nansible_python_interpreter: `which python`\n" >> ./environments/local/group_vars/all
```

#### Preserving configuration and log directories on reboot
When using the local Ansible environment, configuration and log data is stored in `/tmp` by default. However, operating
system such as Linux and Mac clean the `/tmp` directory on reboot, resulting in failures when OpenWhisk tries to start
up again. To avoid this problem, export the `OPENWHISK_TMP_DIR` variable assigning it the path to a persistent
directory before deploying OpenWhisk.

#### Setup
 
This step should be executed once per development environment.
It will generate the `hosts` configuration file based on your environment settings.

> This file is generated automatically for an ephemeral CouchDB instance during `setup.yml`.

The default configuration does not run multiple instances of core components (e.g., controller, invoker, kafka).
You may elect to enable high-availability (HA) mode by passing tne Ansible option `-e mode=HA` when executing this playbook.
This will configure your deployment with multiple instances (e.g., two Kafka instances, and two invokers).

In addition to the host file generation, you need to configure the database for your deployment. This is done
by modifying the file `ansible/db_local.ini` to provide the following properties.

```
[db_creds]
db_provider=
db_username=
db_password=
db_protocol=
db_host=
db_port=
```

For convenience, you can use shell environment variables that are read by the playbook to generate the required `db_local.ini` file as shown below.

```shell script
export OW_DB=CouchDB
export OW_DB_USERNAME=<your couchdb user>
export OW_DB_PASSWORD=<your couchdb password>
export OW_DB_PROTOCOL=<your couchdb protocol>
export OW_DB_HOST=<your couchdb host>
export OW_DB_PORT=<your couchdb port>

ansible-playbook -i environments/$ENVIRONMENT setup.yml
```

##### Use Cloudant as a datastore

```shell script
export OW_DB=Cloudant
export OW_DB_USERNAME=<your cloudant user>
export OW_DB_PASSWORD=<your cloudant password>
export OW_DB_PROTOCOL=https
export OW_DB_HOST=<your cloudant user>.cloudant.com
export OW_DB_PORT=443

ansible-playbook -i environments/$ENVIRONMENT setup.yml
```

#### Install Prerequisites

> This step is not required for local environments since all prerequisites are already installed, and therefore may be skipped.

This step needs to be done only once per target environment. It will install necessary prerequisites on all target hosts in the environment.

```
ansible-playbook -i environments/$ENVIRONMENT prereq.yml
```

**Hint:** During playbook execution the `TASK [prereq : check for pip]` can show as failed. This is normal if no pip is installed. The playbook will then move on and install pip on the target machines.

### Deploying Using CouchDB
-   Make sure your `db_local.ini` file is [setup for](#setup) CouchDB then execute:

```shell script
cd <openwhisk_home>
./gradlew distDocker
cd ansible
ansible-playbook -i environments/$ENVIRONMENT couchdb.yml
ansible-playbook -i environments/$ENVIRONMENT initdb.yml
ansible-playbook -i environments/$ENVIRONMENT wipe.yml
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml

# installs a catalog of public packages and actions
ansible-playbook -i environments/$ENVIRONMENT postdeploy.yml

# to use the API gateway
ansible-playbook -i environments/$ENVIRONMENT apigateway.yml
ansible-playbook -i environments/$ENVIRONMENT routemgmt.yml
```

- You need to run `initdb.yml` **every time** you do a fresh deploy CouchDB to initialize the subjects database.
- The `wipe.yml` playbook should be run on a fresh deployment only, otherwise actions and activations will be lost.
- Run `postdeploy.yml` after deployment to install a catalog of useful packages.
- To use the API Gateway, you'll need to run `apigateway.yml` and `routemgmt.yml`.
- Use `ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml` to avoid wiping the data store. This is useful to start OpenWhisk after restarting your Operating System.

#### Limitation

You cannot run multiple CouchDB nodes on a single machine. This limitation comes from Erlang EPMD which CouchDB relies on to find other nodes.
To deploy multiple CouchDB nodes, they should be placed on different machines respectively otherwise their ports will clash.


### Deploying Using Cloudant
-   Make sure your `db_local.ini` file is set up for Cloudant. See [Setup](#setup).
-   Then execute:

```shell script
cd <openwhisk_home>
./gradlew distDocker
cd ansible
ansible-playbook -i environments/$ENVIRONMENT initdb.yml
ansible-playbook -i environments/$ENVIRONMENT wipe.yml
ansible-playbook -i environments/$ENVIRONMENT apigateway.yml
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml

# installs a catalog of public packages and actions
ansible-playbook -i environments/$ENVIRONMENT postdeploy.yml

# to use the API gateway
ansible-playbook -i environments/$ENVIRONMENT apigateway.yml
ansible-playbook -i environments/$ENVIRONMENT routemgmt.yml
```

- You need to run `initdb` on Cloudant **only once** per Cloudant database to initialize the subjects database.
- The `initdb.yml` playbook will only initialize your database if it is not initialized already, else it will skip initialization steps.
- The `wipe.yml` playbook should be run on a fresh deployment only, otherwise actions and activations will be lost.
- Run `postdeploy.yml` after deployment to install a catalog of useful packages.
- To use the API Gateway, you'll need to run `apigateway.yml` and `routemgmt.yml`.
- Use `ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml` to avoid wiping the data store. This is useful to start OpenWhisk after restarting your Operating System.

### Deploying Using MongoDB

You can choose MongoDB instead of CouchDB as the database backend to store entities.

- Deploy a mongodb server(Optional, for test and develop only, use an external MongoDB server in production).
  You need to execute `pip install pymongo` first

```
ansible-playbook -i environments/<environment> mongodb.yml -e mongodb_data_volume="/tmp/mongo-data"
```

- Then execute

```
cd <openwhisk_home>
./gradlew distDocker
cd ansible
ansible-playbook -i environments/<environment> initMongodb.yml -e mongodb_connect_string="mongodb://172.17.0.1:27017"
ansible-playbook -i environments/<environment> apigateway.yml -e mongodb_connect_string="mongodb://172.17.0.1:27017"
ansible-playbook -i environments/<environment> openwhisk.yml -e mongodb_connect_string="mongodb://172.17.0.1:27017" -e db_artifact_backend="MongoDB"

# installs a catalog of public packages and actions
ansible-playbook -i environments/<environment> postdeploy.yml

# to use the API gateway
ansible-playbook -i environments/<environment> apigateway.yml
ansible-playbook -i environments/<environment> routemgmt.yml
```

Available parameters for ansible are
```
  mongodb:
    connect_string: "{{ mongodb_connect_string }}"
    database: "{{ mongodb_database | default('whisks') }}"
    data_volume: "{{ mongodb_data_volume | default('mongo-data') }}"
```

### Using ElasticSearch to Store Activations

You can use ElasticSearch (ES) to store activations separately while other entities remain stored in CouchDB. There is an Ansible playbook to setup a simple ES cluster for testing and development purposes.

-   Provide your custom ES related ansible arguments:

```
elastic_protocol="http"
elastic_index_pattern="openwhisk-%s" // this will be combined with namespace's name, so different namespace can use different index
elastic_base_volume="esdata" // name of docker volume to store ES data
elastic_cluster_name="openwhisk"
elastic_java_opts="-Xms1g -Xmx1g"
elastic_loglevel="INFO"
elastic_username="admin"
elastic_password="admin"
elasticsearch_connect_string="x.x.x.x:9200,y.y.y.y:9200" // if you want to use an external ES cluster, add it
```

-  Then execute:

```shell script
cd <openwhisk_home>
./gradlew distDocker
cd ansible
# couchdb is still needed to store subjects and actions
ansible-playbook -i environments/$ENVIRONMENT couchdb.yml
ansible-playbook -i environments/$ENVIRONMENT initdb.yml
ansible-playbook -i environments/$ENVIRONMENT wipe.yml
# this will deploy a simple ES cluster, you can skip this to use external ES cluster
ansible-playbook -i environments/$ENVIRONMENT elasticsearch.yml
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml -e db_activation_backend=ElasticSearch

# installs a catalog of public packages and actions
ansible-playbook -i environments/$ENVIRONMENT postdeploy.yml

# to use the API gateway
ansible-playbook -i environments/$ENVIRONMENT apigateway.yml
ansible-playbook -i environments/$ENVIRONMENT routemgmt.yml
```

### Configuring the installation of `wsk` CLI
There are two installation modes to install `wsk` CLI: remote and local.

The mode "remote" means to download the `wsk` binaries from available web links.
By default, OpenWhisk sets the installation mode to remote and downloads the
binaries from the CLI
[release page](https://github.com/apache/openwhisk-cli/releases),
where OpenWhisk publishes the official `wsk` binaries.

The mode "local" means to build and install the `wsk` binaries from local CLI
project. You can download the source code of OpenWhisk CLI
[here](https://github.com/apache/openwhisk-cli).
Let's assume your OpenWhisk CLI home directory is
`$OPENWHISK_HOME/../openwhisk-cli` and you've already `export`ed
`OPENWHISK_HOME` to be the root directory of this project. After you download
the CLI repository, use the gradle command to build the binaries (you can omit
the `-PnativeBuild` if you want to cross-compile for all supported platforms):

```shell script
cd "$OPENWHISK_HOME/../openwhisk-cli"
./gradlew releaseBinaries -PnativeBuild
```

The binaries are generated and put into a tarball in the folder
`../openwhisk-cli/release`.  Then, use the following Ansible command
to (re-)configure the CLI installation:

```shell script
export OPENWHISK_ENVIRONMENT=local  # ... or whatever
ansible-playbook -i environments/$OPENWHISK_ENVIRONMENT edge.yml -e mode=clean
ansible-playbook -i environments/$OPENWHISK_ENVIRONMENT edge.yml \
    -e cli_installation_mode=local \
    -e openwhisk_cli_home="$OPENWHISK_HOME/../openwhisk-cli"
```

The parameter `cli_installation_mode` specifies the CLI installation mode and
the parameter `openwhisk_cli_home` specifies the home directory of your local
OpenWhisk CLI.  (_n.b._ `openwhisk_cli_home` defaults to
`$OPENWHISK_HOME/../openwhisk-cli`.)

Once the CLI is installed, you can [use it to work with Whisk](../docs/cli.md).

### Hot-swapping a Single Component
The playbook structure allows you to clean, deploy or re-deploy a single component as well as the entire OpenWhisk stack. Let's assume you have deployed the entire stack using the `openwhisk.yml` playbook. You then make a change to a single component, for example the invoker. You will probably want a new tag on the invoker image so you first build it using:

```shell script
cd <openwhisk_home>
gradle :core:invoker:distDocker -PdockerImageTag=myNewInvoker
```
Then all you need to do is re-deploy the invoker using the new image:

```shell script
cd ansible
ansible-playbook -i environments/$ENVIRONMENT invoker.yml -e docker_image_tag=myNewInvoker
```

**Hint:** You can omit the Docker image tag parameters in which case `latest` will be used implicitly.

### Cleaning a Single Component
You can remove a single component just as you would remove the entire deployment stack.
For example, if you wanted to remove only the controller you would run:

```shell script
cd ansible
ansible-playbook -i environments/$ENVIRONMENT controller.yml -e mode=clean
```

**Caveat:** In distributed environments some components (e.g. Invoker, etc.) exist on multiple machines. So if you run a playbook to clean or deploy those components, it will run on **all** of the hosts targeted by the component's playbook.


### Cleaning an OpenWhisk Deployment
Once you are done with the deployment you can clean it from the target environment.

```shell script
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml -e mode=clean
```

### Removing all prereqs from an environment
This is usually not necessary, however in case you want to uninstall all prereqs from a target environment, execute:

```shell script
ansible-playbook -i environments/$ENVIRONMENT prereq.yml -e mode=clean
```

### Lean Setup
To have a lean setup (no Kafka, Zookeeper and no Invokers as separate entities):

At [Deploying Using CouchDB](ansible/README.md#deploying-using-cloudant) step, replace:
```shell script
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml
```
by:
```shell script
ansible-playbook -i environments/$ENVIRONMENT openwhisk.yml -e lean=true
```

### Troubleshooting
Some of the more common problems and their solution are listed here.

#### Setuptools Version Mismatch
If you encounter the following error message during `ansible` execution

```
ERROR! Unexpected Exception: ... Requirement.parse('setuptools>=11.3'))
```

your `setuptools` package is likely out of date. You can upgrade the package using this command:

```shell script
pip install --upgrade setuptools --user python
```


#### Mac Setup - Python Interpreter
The MacOS environment assumes Python is installed in `/usr/local/bin` which is the default location when using `brew`.
The following error will occur if Python is located elsewhere:

```
ansible all -i environments/mac -m ping
ansible | FAILED! => {
    "changed": false,
    "failed": true,
    "module_stderr": "/bin/sh: /usr/local/bin/python: No such file or directory\n",
    "module_stdout": "",
    "msg": "MODULE FAILURE",
    "parsed": false
}
```

An expedient workaround is to create a link to the expected location:

```shell script
ln -s $(which python) /usr/local/bin/python
```

Alternatively, you can also configure the location of Python interpreter in `environments/<environment>/group_vars`.

```shell script
ansible_python_interpreter: "/usr/local/bin/python"
```

#### Failed to import docker-py

After `brew install ansible`, the following lines are printed out:

```
==> Caveats
If you need Python to find the installed site-packages:
  mkdir -p ~/Library/Python/2.7/lib/python/site-packages
  echo '/usr/local/lib/python2.7/site-packages' > ~/Library/Python/2.7/lib/python/site-packages/homebrew.pth
```

Just run the two commands to fix this issue.

#### Spaces in Paths
Ansible 2.1.0.0 and earlier versions do not support a space in file paths.
Many file imports and roles will not work correctly when included from a path that contains spaces.
If you encounter this error message during Ansible execution

```
fatal: [ansible]: FAILED! => {"failed": true, "msg": "need more than 1 value to unpack"}
```

the path to your OpenWhisk `ansible` directory contains spaces. To fix this, please copy the source tree to a path
without spaces as there is no current fix available to this problem.

#### Changing limits
The default system throttling limits are configured in this file [./group_vars/all](./group_vars/all) and may be changed by modifying the group_vars for your specific environment.
```
limits:
  invocationsPerMinute: "{{ limit_invocations_per_minute | default(60) }}"
  concurrentInvocations: "{{ limit_invocations_concurrent | default(30) }}"
  firesPerMinute: "{{ limit_fires_per_minute | default(60) }}"
  sequenceMaxLength: "{{ limit_sequence_max_length | default(50) }}"
```
- The `limits.invocationsPerMinute` represents the allowed namespace action invocations per minute.
- The `limits.concurrentInvocations` represents the maximum concurrent invocations allowed per namespace.
- The `limits.firesPerMinute` represents the allowed namespace trigger firings per minute.
- The `limits.sequenceMaxLength` represents the maximum length of a sequence action.

#### Set the timezone for containers
The default timezone for all system containers is UTC. The timezone may differ from your servers which could make it difficult to inspect logs. The timezone is configured globally in [group_vars/all](./group_vars/all#L280) or by passing an extra variable `-e docker_timezone=xxx` when you run an ansible-playbook.
