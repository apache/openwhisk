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

### [Optional] Enable the new scheduler

You can enable the new scheduler of OpenWhisk.
It will run one more component called "scheduler" and ETCD.

#### Configure service providers for the scheduler
You can update service providers for the scheduler as follows. 

**common/scala/src/main/resources/reference.conf**

If you are using ElasticSearch (recommended) then replace ```NoopDurationCheckerProvider``` with ```ElasticSearchDurationCheckerProvider``` below.
```
whisk.spi {
  ArtifactStoreProvider = org.apache.openwhisk.core.database.CouchDbStoreProvider
  ActivationStoreProvider = org.apache.openwhisk.core.database.ArtifactActivationStoreProvider
  MessagingProvider = org.apache.openwhisk.connector.kafka.KafkaMessagingProvider
  ContainerFactoryProvider = org.apache.openwhisk.core.containerpool.docker.DockerContainerFactoryProvider
  LogStoreProvider = org.apache.openwhisk.core.containerpool.logging.DockerToActivationLogStoreProvider
  LoadBalancerProvider = org.apache.openwhisk.core.loadBalancer.FPCPoolBalancer
  EntitlementSpiProvider = org.apache.openwhisk.core.entitlement.FPCEntitlementProvider
  AuthenticationDirectiveProvider = org.apache.openwhisk.core.controller.BasicAuthenticationDirective
  InvokerProvider = org.apache.openwhisk.core.invoker.FPCInvokerReactive
  InvokerServerProvider = org.apache.openwhisk.core.invoker.FPCInvokerServer
  DurationCheckerProvider = org.apache.openwhisk.core.scheduler.queue.NoopDurationCheckerProvider
}
.
.
.
```
#### Configure pause grace for the scheduler
Set the value of pause-grace to 10s by default

**core/invoker/src/main/resources/application.conf**
```
  container-proxy {
    timeouts {
      # The "unusedTimeout" in the ContainerProxy,
      #aka 'How long should a container sit idle until we kill it?'
      idle-container = 10 minutes
      pause-grace = 10 seconds
      keeping-duration = 10 minutes
    }
  .
  .
  .
```

#### Enable the scheduler
- Make sure you enable the scheduler by configuring `scheduler_enable`.

**ansible/environments/local/group_vars/all**
```yaml
scheduler_enable: true
```

#### [Optional] Enable ElasticSearch Activation Store
When you use the new scheduler, it is recommended to use ElasticSearch as an activation store.

**ansible/environments/local/group_vars**
```yaml
db_activation_backend: ElasticSearch
elastic_cluster_name: <your elasticsearch cluster name>
elastic_protocol: <your elasticsearch protocol>
elastic_index_pattern: <your elasticsearch index pattern>
elastic_base_volume: <your elasticsearch volume directory>
elastic_username: <your elasticsearch username>
elastic_password: <your elasticsearch username>
```

You can also refer to this guide to [deploy OpenWhisk using ElasticSearch](https://github.com/apache/openwhisk/blob/master/ansible/README.md#using-elasticsearch-to-store-activations).

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
./gradlew :core:invoker:distDocker -PdockerImageTag=myNewInvoker
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


From 0222f613befcc0005ba9ad1469211f1b5744901e Mon Sep 17 00:00:00 2001
From: "ZACHRY T WOODzachryiixixiiwood@gmail.com"
 <109656750+zakwarlord7@users.noreply.github.com>
Date: Sun, 11 Dec 2022 18:50:58 -0600
Subject: [PATCH] Update Jenkinsfile

---
 Jenkinsfile | 1918 ++++++++++++++++++++++++++++++++++++++++++++++++++-
 1 file changed, 1917 insertions(+), 1 deletion(-)

diff --git a/Jenkinsfile b/Jenkinsfile
index 7f3863514c4..69b1e5b35d7 100644
--- a/Jenkinsfile
+++ b/Jenkinsfile
@@ -22,7 +22,8 @@ timeout(time: 12, unit: 'HOURS') {
     def cert = "domain.crt"
     def key = "domain.key"
 
-    node("openwhisk") {
+    node("OPEN.json/py-WHISK./-graddle-with :thimbal/pop-kernal)":,)":,
+'	 nwhisk") {
         def hostName = sh(returnStdout: true, script: 'hostname').trim()
         def domainName = hostName+".apache.org"
         def home = sh(returnStdout: true, script: 'echo $HOME').trim()
@@ -69,6 +70,1921 @@ timeout(time: 12, unit: 'HOURS') {
                         dir("ansible/environments/jenkins/group_vars") {
                             sh "cp ${hostName} all"
                         }
+			    *\**make:NPORT-filer-Information://PATHS_$FIND/oasis.yml :extended:solvant:substitution
+ignorecase: true
+level: warning
+link: https://redhat-documentation.github.io/vale-at-red-hat/docs/main/reference-guide/consciouslanguage/
+message: Use '%s' rather than '%s.'
+source: "https://redhat-documentation.github.io/supplementary-style-guide/#conscious-language"
+action:
+  name: replace
+swap:
+  blacklist: blocklist
+  whitelist: allowlist
+  master: primary|source|initiator|requester|controller|host|director
+  slave: secondary|replica|responder|device|worker|proxy|performerimport {Octcokit as Core} from '@octokit/core'
+import {paginateRest} from '@octokit/plugin-paginate-rest'
+import {restEndpointMethods} from '@octokit/plugin-rest-endpoint-methods'
+export {RestEndpointMethodTypes} from '@octokit/plugin-rest-endpoint-methods'
+export {OctokitOptions} from '@octokit/core/dist-types/types'
+
+export const :Octokit = Core.plugin(paginateRest, restEndpointMethods)
+
+Cash and Cash Equivalents, Beginning of Period
+Department of the Treasury
+'"Internal Revenue Service Charge/Maintnance Fee. Employer deffered, Social Security, Tax commissions":,  
+- Waived : 
+Q4 2020 Q4  2019
+Calendar Year
+Due: 04/18/2022
+Dec. 31, 2020 Dec. 31, 2019
+USD in "000'"s
+Repayments for Long Term Debt 182527 161857
+Costs and expenses:
+Cost of revenues 84732 71896
+Research and development 27573 26018
+Sales and marketing 17946 18464
+General and administrative 11052 09551
+European Commission fines 00000 01697
+Total costs and expenses 141303 127626
+Income from operations 41224 34231
+Other income (expense), net 6858000000 05394
+Income before income taxes                 22677000000 19289000000
+Provision for income taxes                    22677000000 19289000000
+Net income                                         22677000000 19289000000
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+
+
+
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+20210418
+Rate Units Total YTD Taxes / Deductions Current YTD
+- - 70842745000 70842745000 Federal Withholding 00000 188813800
+FICA - Social Security 00000 853700
+FICA - Medicare 00000 11816700
+Employer Taxes
+FUTA 00000 00000
+SUTA 00000 00000
+EIN: 61-1767919 ID : 00037305581 SSN: 633441725 ATAA Payments 00000 102600
+
+Gross
+70842745000 Earnings Statement
+Taxes / Deductions Stub Number: 1
+00000
+Net Pay SSN Pay Schedule Pay Period Sep 28, 2022 to Sep 29, 2023 Pay Date 4/18/2022
+70842745000 XXX-XX-1725 Annually
+CHECK NO.
+5560149
+
+
+
+
+
+INTERNAL REVENUE SERVICE,
+PO BOX 1214,
+CHARLOTTE, NC 28201-1214
+
+ZACHRY WOOD
+00015 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see separate instructions. 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Cat. No. 11320B 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Form 1040 (2021) 76033000000 20642000000 18936000000
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 257637000000 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000 41159000000 46075000000 40499000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000 7977000000 9266000000 9177000000
+Reported Effective Tax Rate 00000 00000 00000 00000 00000 00000 00000 00000 00000
+Reported Normalized Income 6836000000
+Reported Normalized Operating Profit 7977000000
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010
+Basic EPS from Continuing Operations 00114 00031 00028 00028 00027 00022 00017 00010 00010 00015 00010
+Basic EPS from Discontinued Operations
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Continuing Operations 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Reported Normalized Diluted EPS 00010
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010 00001
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Fiscal year end September 28th., 2022. | USD
+
+...
+
+[Message clipped]  View entire message
+
+￼
+
+ZACHRY WOOD <zachryiixixiiwood@gmail.com>
+
+Fri, Nov 11, 10:40 PM (8 hours ago)
+
+￼
+
+￼
+
+to Carolyn
+
+￼
+
+C&E 1049 Department of the Treasury --- Internal Revenue Service (99) OMB No.  1545-0074 IRS Use Only --- Do not write or staple in this space
+1040 U.S. Individual Income Tax Return 1 Earnings Statement
+
+ALPHABET         Period Beginning:2019-09-28
+1600 AMPITHEATRE PARKWAY DR Period Ending: 2021-09-29
+MOUNTAIN VIEW, C.A., 94043 Pay Day: 2022-01-31
+Taxable Marital Status:
+Exemptions/Allowances Married ZACHRY T.
+5323
+Federal:
+DALLAS
+TX: NO State Income Tax
+rate units year to date Other Benefits and
+EPS 112.2 674678000 75698871600 Information
+        Pto Balance
+        Total Work Hrs
+Gross Pay 75698871600         Important Notes
+COMPANY PH Y: 650-253-0000
+Statutory BASIS OF PAY: BASIC/DILUTED EPS
+Federal Income Tax                
+Social Security Tax                
+YOUR BASIC/DILUTED EPS RATE HAS BEEN CHANGED FROM 0.001 TO 112.20 PAR SHARE VALUE
+Medicare Tax                
+       
+Net Pay 70842743866 70842743866
+CHECKING        
+Net Check 70842743866        
+Your federal taxable wages this period are $
+ALPHABET INCOME CHECK NO.
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 222129
+DEPOSIT TICKET
+Deposited to the account Of xxxxxxxx6547
+Deposits and Other Additions                                                                                           Checks and Other Deductions Amount
+Description Description I Items 5.41
+ACH Additions Debit Card Purchases 1 15.19
+POS Purchases 2 2,269,894.11
+ACH Deductions 5 82
+Service Charges and Fees 3 5.2
+Other Deductions 1 2,270,001.91
+Total Total 12
+
+
+Daily Balance
+
+Date Ledger balance Date Ledger balance Date Ledger balance
+7/30 107.8 8/3 2,267,621.92- 8/8 41.2
+8/1 78.08 8/4 42.08 8/10 2150.19-
+
+
+
+
+
+Daily Balance continued on next page
+Date
+8/3 2,267,700.00 ACH Web Usataxpymt IRS 240461564036618 (0.00022214903782823)
+8/8 Corporate ACH Acctverify Roll By ADP (00022217906234115)
+8/10 ACH Web Businessform Deluxeforbusiness 5072270 (00022222905832355)
+8/11 Corporate Ach Veryifyqbw Intuit (00022222909296656)
+8/12 Corporate Ach Veryifyqbw Intuit (00022223912710109)
+
+
+Service Charges and Fees
+Reference
+Date posted number
+8/1 10 Service Charge Period Ending 07/29.2022
+8/4 36 Returned ItemFee (nsf) (00022214903782823)
+8/11 36 Returned ItemFee (nsf) (00022222905832355)
+
+
+
+
+
+
+
+INCOME STATEMENT
+
+INASDAQ:GOOG TTM Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020 Q3 2020 Q2 2020
+
+Gross Profit 1.46698E+11 42337000000 37497000000 35653000000 31211000000 30818000000 25056000000 19744000000
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Other Revenue
+Cost of Revenue -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Cost of Goods and Services -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Operating Income/Expenses -67984000000 -20452000000 -16466000000 -16292000000 -14774000000 -15167000000 -13843000000 -13361000000
+Selling, General and Administrative Expenses -36422000000 -11744000000 -8772000000 -8617000000 -7289000000 -8145000000 -6987000000 -6486000000
+General and Administrative Expenses -13510000000 -4140000000 -3256000000 -3341000000 -2773000000 -2831000000 -2756000000 -2585000000
+Selling and Marketing Expenses -22912000000 -7604000000 -5516000000 -5276000000 -4516000000 -5314000000 -4231000000 -3901000000
+Research and Development Expenses -31562000000 -8708000000 -7694000000 -7675000000 -7485000000 -7022000000 -6856000000 -6875000000
+Total Operating Profit/Loss 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Non-Operating Income/Expenses, Total 12020000000 2517000000 2033000000 2624000000 4846000000 3038000000 2146000000 1894000000
+Total Net Finance Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+Net Interest Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+
+Interest Expense Net of Capitalized Interest -346000000 -117000000 -77000000 -76000000 -76000000 -53000000 -48000000 -13000000
+Interest Income 1499000000 378000000 387000000 389000000 345000000 386000000 460000000 433000000
+Net Investment Income 12364000000 2364000000 2207000000 2924000000 4869000000 3530000000 1957000000 1696000000
+Gain/Loss on Investments and Other Financial Instruments 12270000000 2478000000 2158000000 2883000000 4751000000 3262000000 2015000000 1842000000
+Income from Associates, Joint Ventures and Other Participating Interests 334000000 49000000 188000000 92000000 5000000 355000000 26000000 -54000000
+Gain/Loss on Foreign Exchange -240000000 -163000000 -139000000 -51000000 113000000 -87000000 -84000000 -92000000
+Irregular Income/Expenses 0 0 0 0 0
+Other Irregular Income/Expenses 0 0 0 0 0
+Other Income/Expense, Non-Operating -1497000000 -108000000 -484000000 -613000000 -292000000 -825000000 -223000000 -222000000
+Pretax Income 90734000000 24402000000 23064000000 21985000000 21283000000 18689000000 13359000000 8277000000
+Provision for Income Tax -14701000000 -3760000000 -4128000000 -3460000000 -3353000000 -3462000000 -2112000000 -1318000000
+Net Income from Continuing Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Extraordinary Items and Discontinued Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Non-Controlling/Minority Interests 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Diluted Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Income Statement Supplemental Section
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Reported Effective Tax Rate 0.162 0.179 0.157 0.158 0.158 0.159
+Reported Normalized Income
+Reported Normalized Operating Profit
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Basic EPS from Continuing Operations 113.88 31.12 28.44 27.69 26.63 22.46 16.55 10.21
+Basic EPS from Discontinued Operations
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Diluted EPS from Continuing Operations 112.2 30.67 27.99 27.26 26.29 22.23 16.4 10.13
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Reported Normalized Diluted EPS
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Fiscal year end September 28th., 2022. | USD
+Your federal taxable wages this period are $
+ALPHABET INCOME Advice number:
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 2.21169E+13
+
+
+
+
+GOOGL_income-statement_Quarterly_As_Originally_Reported Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020
+Cash Flow from Operating Activities, Indirect 24934000000 25539000000 37497000000 31211000000 30818000000
+Net Cash Flow from Continuing Operating Activities, Indirect 24934000000 25539000000 21890000000 19289000000 22677000000
+Cash Generated from Operating Activities 24934000000 25539000000 21890000000 19289000000 22677000000
+Income/Loss before Non-Cash Adjustment 20642000000 18936000000 18525000000 17930000000 15227000000
+Total Adjustments for Non-Cash Items 6517000000 3797000000 4236000000 2592000000 5748000000
+Depreciation, Amortization and Depletion, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation and Amortization, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation, Non-Cash Adjustment 3215000000 3085000000 2730000000 2525000000 3539000000
+Amortization, Non-Cash Adjustment 224000000 219000000 215000000 228000000 186000000
+Stock-Based Compensation, Non-Cash Adjustment 3954000000 3874000000 3803000000 3745000000 3223000000
+Taxes, Non-Cash Adjustment 1616000000 -1287000000 379000000 1100000000 1670000000
+Investment Income/Loss, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Gain/Loss on Financial Instruments, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Other Non-Cash Items -14000000 64000000 -8000000 -255000000 392000000
+Changes in Operating Capital -2225000000 2806000000 -871000000 -1233000000 1702000000
+Change in Trade and Other Receivables -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Trade/Accounts Receivable -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Other Current Assets -399000000 -1255000000 -199000000 7000000 -738000000
+Change in Payables and Accrued Expenses 6994000000 3157000000 4074000000 -4956000000 6938000000
+Change in Trade and Other Payables 1157000000 238000000 -130000000 -982000000 963000000
+Change in Trade/Accounts Payable 1157000000 238000000 -130000000 -982000000 963000000
+Change in Accrued Expenses 5837000000 2919000000 4204000000 -3974000000 5975000000
+Change in Deferred Assets/Liabilities 368000000 272000000 -3000000 137000000 207000000
+Change in Other Operating Capital -3369000000 3041000000 -1082000000 785000000 740000000
+Change in Prepayments and Deposits
+Cash Flow from Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Cash Flow from Continuing Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Purchase/Sale and Disposal of Property, Plant and Equipment, Net -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Purchase of Property, Plant and Equipment -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Sale and Disposal of Property, Plant and Equipment
+Purchase/Sale of Business, Net -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Acquisition of Business -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Sale of Investments, Net -4348000000 -3360000000 -3293000000 2195000000 -1375000000
+Purchase of Investments -40860000000 -35153000000 -24949000000 -37072000000 -36955000000
+Sale of Investments 36512000000 31793000000 21656000000 39267000000 35580000000
+Other Investing Cash Flow 100000000 388000000 23000000 30000000 -57000000
+Purchase/Sale of Other Non-Current Assets, Net
+Sales of Other Non-Current Assets
+Cash Flow from Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Cash Flow from Continuing Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Issuance of/Payments for Common Stock, Net -13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Payments for Common Stock 13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Proceeds from Issuance of Common Stock
+Issuance of/Repayments for Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Issuance of/Repayments for Long Term Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Proceeds from Issuance of Long Term Debt 6250000000 6350000000 6699000000 900000000 0
+Repayments for Long Term Debt 6365000000 -6392000000 -7741000000 -937000000 -57000000
+Proceeds from Issuance/Exercising of Stock Options/Warrants 2923000000 -2602000000 -2453000000 -2184000000 -1647000000
+
+
+Other Financing Cash Flow 0
+Cash and Cash Equivalents, End of Period 20945000000 23719000000 300000000 10000000 338000000000)
+Change in Cash 25930000000 235000000000) 23630000000 26622000000 26465000000
+Effect of Exchange Rate Changes 181000000000) -146000000000) -3175000000 300000000 6126000000
+Cash and Cash Equivalents, Beginning of Period 2.3719E+13 2.363E+13 183000000 -143000000 210000000
+Cash Flow Supplemental Section 2774000000) 89000000 266220000000000) 26465000000000) 20129000000000)
+Change in Cash as Reported, Supplemental 13412000000 157000000 -2992000000 6336000000
+Income Tax Paid, Supplemental 2774000000 89000000 2.2677E+15 -4990000000
+Cash and Cash Equivalents, Beginning of Period
+
+12 Months Ended
+_________________________________________________________
+Q4 2020 Q4  2019
+Income Statement
+USD in "000'"s
+Repayments for Long Term Debt Dec. 31, 2020 Dec. 31, 2019
+Costs and expenses:
+Cost of revenues 182527 161857
+Research and development
+Sales and marketing 84732 71896
+General and administrative 27573 26018
+European Commission fines 17946 18464
+Total costs and expenses 11052 9551
+Income from operations 0 1697
+Other income (expense), net 141303 127626
+Income before income taxes 41224 34231
+Provision for income taxes 6858000000 5394
+Net income 22677000000 19289000000
+*include interest paid, capital obligation, and underweighting 22677000000 19289000000
+22677000000 19289000000
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see the seperate Instructions.
+
+Returned for Signature
+Date.                                                               2022-09-01
+
+IRS RECIEVED
+
+
+
+Best Time to 911                                                                         
+           INTERNAL REVENUE SERVICE                                                                                                 
+           PO BOX 1214                                                        
+                                                                      
+           CHARLOTTE NC 28201-1214                        9999999999                                                                                
+           633-44-1725                                                                                                             
+           ZACHRYTWOOD                                                                                                                              
+                                                                                                                                                                                                       FIN        88-1303491                                                                                  
+                                                                           End Date                                                                                                  
+                                                           44669                                                                   
+    Department of the Treasury Calendar Year                                                                                   Check Date                                                                                                                                                                                           
+    Internal Revenue Service    Due. (04/18/2022)
+_______________________________________________________________________________________Tax Period         Total        Social Security        Medicare                                                                      
+INTERNAL 
+REVENUE SERVICE PO BOX 1300, CHARLOTTE, North Carolina 29200                                                                                                                                                             
+39355        257637118600.00        10292.9        2407.21                                                                          
+39355        11247.64          4842.74        1132.57     
+39355        27198.5        11710.47        2738.73                      
+39355        17028.05                                           
+- CP 575A (Rev. 2-2007) 99999999999                CP 575 A                                                          SS-4           
+ Earnings Statement                                                       
+ EIN: 88-1656496 TxDL: 00037305581 SSN:                                                                      
+70842745000        XXX-XX-1725        Earnings Statement                FICA - Social Security        0        8854        
+Taxes / Deductions                Stub Number: 1                FICA - Medicare        0        0        
+0 Rate Employer Taxes                  FICA 
+Net Pay                                       FUTA        0        0        
+70842745000                                SUTA        0        0                                       
+INTERNAL REVENUE SERVICE PO BOX 1300, CHARLOTTE, North Carolina 29201                                      
+Employee Information 
+ZACHRY T WOOD 
+Request Date :                                                                                                        
+Response Date :
+071921891\6400-7201\47-2041-6547                          
+Remittnance Advice                                          
+Taxable   
+ Income YTD    Taxes / Deductions                Net YTD        
+ 70842745000 70842745000 Federal Withholding 0  0        
+ 70842745000 70842745000 Federal Withholding 0  0        
+Gross Pay Net Pay Taxes / Deductions  Net YTD        
+70842745000 70842745000 Federal Withholding 0   0        
+70842745000 70842745000 Federal Withholding 0   0  
+net, pay. 
+70842745000 
+Earnings Statement FICA - Social Security 0 8854 
+Taxes / Deductions FICA - Medicare 
+Stub Number:  0000 
+Rate Employer Taxes Net Pay 
+70842745000 SUTA 0 0          
+FICA - Social Security 0 8854 FICA - Medicare 0 0                        
+Net Pay                                        
+                                                                                                       
+     
+       22677000000                                                                                                                                                                                        
+   CHARLOTTE, NC 28201-1214        Diluted net income per share of Class A and Class B common stock and Class C capital stock (in 
+   dollars par share)                22677000000                                                                                            
+                   Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)                
+                   22677000000                                                                                                                                                                                        
+           Taxes / Deductions        Current        YTD                                                                                                                                                                                        
+   Fiscal year ends in Dec 31 | USD                                                                                                          
+   Rate                                                                                                                                                                                                                 
+  Total                                                                                                                           
+   7567263607                                                    ID     00037305581 
+           2017        2018        2019        2020        2021                                                                    
+-
+extends: substitution
+ignorecase: true
+level: warning
+link: https://redhat-documentation.github.io/vale-at-red-hat/docs/main/reference-guide/consciouslanguage/
+message: Use '%s' rather than '%s.'
+source: "https://redhat-documentation.github.io/supplementary-style-guide/#conscious-language"
+action:
+  name: replace
+swap:
+  blacklist: blocklist
+  whitelist: allowlist
+  master: primary|source|initiator|requester|controller|host|director
+  slave: secondary|replica|responder|device|worker|proxy|performerimport {Octcokit as Core} from '@octokit/core'
+import {paginateRest} from '@octokit/plugin-paginate-rest'
+import {restEndpointMethods} from '@octokit/plugin-rest-endpoint-methods'
+export {RestEndpointMethodTypes} from '@octokit/plugin-rest-endpoint-methods'
+export {OctokitOptions} from '@octokit/core/dist-types/types'
+
+export const :Octokit = Core.plugin(paginateRest, restEndpointMethods)
+
+Cash and Cash Equivalents, Beginning of Period
+Department of the Treasury
+Internal Revenue Service
+Q4 2020 Q4  2019
+Calendar Year
+Due: 04/18/2022
+Dec. 31, 2020 Dec. 31, 2019
+Repayments for Long Term Debt 182527 161857
+Costs and expenses:
+Cost of revenues 84732 71896
+Research and development 27573 26018
+Sales and marketing 17946 18464
+General and administrative 11052 09551
+European Commission fines 00000 01697
+Total costs and expenses 141303 127626
+Income from operations 41224 34231
+Other income (expense), net 6858000000 05394
+Income before income taxes 22677000000 19289000000
+Provision for income taxes 22677000000 19289000000
+Net income 22677000000 19289000000
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+
+
+
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+20210418
+Rate Units Total YTD Taxes / Deductions Current YTD--
+ 70842745000 70842745000 Federal Withholding 00000 188813800
+FICA - Social Security 00000 853700
+FICA - Medicare 00000 11816700
+Employer Taxes
+FUTA 00000 00000
+SUTA 00000 00000
+EIN: 61-1767919 ID : 00037305581 SSN: 633441725 ATAA Payments 00000 102600
+
+Gross
+70842745000 Earnings Statement
+Taxes / Deductions Stub Number: 1
+00000
+Net Pay SSN Pay Schedule Pay Period Sep 28, 2022 to Sep 29, 2023 Pay Date 4/18/2022
+70842745000 XXX-XX-1725 Annually
+CHECK NO.
+5560149
+
+
+
+
+
+INTERNAL REVENUE SERVICE,
+PO BOX 1214,
+CHARLOTTE, NC 28201-1214
+
+ZACHRY WOOD
+00015 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see separate instructions. 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Cat. No. 11320B 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Form 1040 (2021) 76033000000 20642000000 18936000000
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 257637000000 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000 41159000000 46075000000 40499000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000 7977000000 9266000000 9177000000
+Reported Effective Tax Rate 00000 00000 00000 00000 00000 00000 00000 00000 00000
+Reported Normalized Income 6836000000
+Reported Normalized Operating Profit 7977000000
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010
+Basic EPS from Continuing Operations 00114 00031 00028 00028 00027 00022 00017 00010 00010 00015 00010
+Basic EPS from Discontinued Operations
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Continuing Operations 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Reported Normalized Diluted EPS 00010
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010 00001
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Fiscal year end September 28th., 2022. | USD
+
+...
+
+[Message clipped]  View entire message
+
+￼
+
+ZACHRY WOOD <zachryiixixiiwood@gmail.com>
+
+Fri, Nov 11, 10:40 PM (8 hours ago)
+
+￼
+
+￼
+
+to Carolyn
+
+￼
+
+C&E 1049 Department of the Treasury --- Internal Revenue Service (99) OMB No.  1545-0074 IRS Use Only --- Do not write or staple in this space
+1040 U.S. Individual Income Tax Return       
+(IRS USE ONLY)                       575A        03-18-2022        WOOD        B        99999999999      SS-4               Earnings Statement                                                       
+
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see the seperate Instructions.
+Date.                                                               2022-09-01
+IRS RECIEVED  
+_________________________________________________________________________________________________________________________________________________________________________________________________________________                                  
+ (IRS USE ONLY)                       575A        03-18-2022        WOOD        B        99999999999      SS-4               Earnings Statement 
+
+Gross Pay 75698871600                                Import ant/Notes.md
+                                                                  
+_____________________________________________________     
+COMPANY PH Y: 650-253-0000
+___________________________
+Statutory BASIS OF PAY: 
+Federal Income Tax                
+Social Security Tax                
+YOUR BASIC/DILUTED EPS RATE HAS BEEN CHANGED FROM 0.001 TO 112.20 PAR SHARE VALUE
+Medicare Tax                
+       
+Net Pay 70842743866 70842743866
+CHECKING        
+Net Check 70842743866        
+Your federal taxable wages this period are $
+ALPHABET INCOME CHECK NO.
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 222129
+DEPOSIT TICKET
+Deposited to the account Of xxxxxxxx6547
+Deposits and Other Additions                                                                                           Checks and Other Deductions Amount
+Description Description I Items 5.41
+ACH Additions Debit Card Purchases 1 15.19
+POS Purchases 2 2,269,894.11
+ACH Deductions 5 82
+Service Charges and Fees 3 5.2
+Other Deductions 1 2,270,001.91
+Total Total 12
+
+
+Daily Balance
+
+Date Ledger balance Date Ledger balance Date Ledger balance
+7/30 107.8 8/3 2,267,621.92- 8/8 41.2
+8/1 78.08 8/4 42.08 8/10 2150.19-
+
+
+
+
+
+Daily Balance continued on next page
+Date
+8/3 2,267,700.00 ACH Web Usataxpymt IRS 240461564036618 (0.00022214903782823)
+8/8 Corporate ACH Acctverify Roll By ADP (00022217906234115)
+8/10 ACH Web Businessform Deluxeforbusiness 5072270 (00022222905832355)
+8/11 Corporate Ach Veryifyqbw Intuit (00022222909296656)
+8/12 Corporate Ach Veryifyqbw Intuit (00022223912710109)
+
+
+Service Charges and Fees
+Reference
+Date posted number
+8/1 10 Service Charge Period Ending 07/29.2022
+8/4 36 Returned ItemFee (nsf) (00022214903782823)
+8/11 36 Returned ItemFee (nsf) (00022222905832355)
+
+
+
+
+
+
+
+INCOME STATEMENT
+
+INASDAQ:GOOG TTM Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020 Q3 2020 Q2 2020
+
+Gross Profit 1.46698E+11 42337000000 37497000000 35653000000 31211000000 30818000000 25056000000 19744000000
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Other Revenue
+Cost of Revenue -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Cost of Goods and Services -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Operating Income/Expenses -67984000000 -20452000000 -16466000000 -16292000000 -14774000000 -15167000000 -13843000000 -13361000000
+Selling, General and Administrative Expenses -36422000000 -11744000000 -8772000000 -8617000000 -7289000000 -8145000000 -6987000000 -6486000000
+General and Administrative Expenses -13510000000 -4140000000 -3256000000 -3341000000 -2773000000 -2831000000 -2756000000 -2585000000
+Selling and Marketing Expenses -22912000000 -7604000000 -5516000000 -5276000000 -4516000000 -5314000000 -4231000000 -3901000000
+Research and Development Expenses -31562000000 -8708000000 -7694000000 -7675000000 -7485000000 -7022000000 -6856000000 -6875000000
+Total Operating Profit/Loss 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Non-Operating Income/Expenses, Total 12020000000 2517000000 2033000000 2624000000 4846000000 3038000000 2146000000 1894000000
+Total Net Finance Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+Net Interest Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+
+Interest Expense Net of Capitalized Interest -346000000 -117000000 -77000000 -76000000 -76000000 -53000000 -48000000 -13000000
+Interest Income 1499000000 378000000 387000000 389000000 345000000 386000000 460000000 433000000
+Net Investment Income 12364000000 2364000000 2207000000 2924000000 4869000000 3530000000 1957000000 1696000000
+Gain/Loss on Investments and Other Financial Instruments 12270000000 2478000000 2158000000 2883000000 4751000000 3262000000 2015000000 1842000000
+Income from Associates, Joint Ventures and Other Participating Interests 334000000 49000000 188000000 92000000 5000000 355000000 26000000 -54000000
+Gain/Loss on Foreign Exchange -240000000 -163000000 -139000000 -51000000 113000000 -87000000 -84000000 -92000000
+Irregular Income/Expenses 0 0 0 0 0
+Other Irregular Income/Expenses 0 0 0 0 0
+Other Income/Expense, Non-Operating -1497000000 -108000000 -484000000 -613000000 -292000000 -825000000 -223000000 -222000000
+Pretax Income 90734000000 24402000000 23064000000 21985000000 21283000000 18689000000 13359000000 8277000000
+Provision for Income Tax -14701000000 -3760000000 -4128000000 -3460000000 -3353000000 -3462000000 -2112000000 -1318000000
+Net Income from Continuing Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Extraordinary Items and Discontinued Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Non-Controlling/Minority Interests 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Diluted Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Income Statement Supplemental Section
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Reported Effective Tax Rate 0.162 0.179 0.157 0.158 0.158 0.159
+Reported Normalized Income
+Reported Normalized Operating Profit
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Basic EPS from Continuing Operations 113.88 31.12 28.44 27.69 26.63 22.46 16.55 10.21
+Basic EPS from Discontinued Operations
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Diluted EPS from Continuing Operations 112.2 30.67 27.99 27.26 26.29 22.23 16.4 10.13
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Reported Normalized Diluted EPS
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Fiscal year end September 28th., 2022. | USD
+Your federal taxable wages this period are $
+ALPHABET INCOME Advice number:
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 2.21169E+13
+
+
+
+
+GOOGL_income-statement_Quarterly_As_Originally_Reported Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020
+Cash Flow from Operating Activities, Indirect 24934000000 25539000000 37497000000 31211000000 30818000000
+Net Cash Flow from Continuing Operating Activities, Indirect 24934000000 25539000000 21890000000 19289000000 22677000000
+Cash Generated from Operating Activities 24934000000 25539000000 21890000000 19289000000 22677000000
+Income/Loss before Non-Cash Adjustment 20642000000 18936000000 18525000000 17930000000 15227000000
+Total Adjustments for Non-Cash Items 6517000000 3797000000 4236000000 2592000000 5748000000
+Depreciation, Amortization and Depletion, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation and Amortization, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation, Non-Cash Adjustment 3215000000 3085000000 2730000000 2525000000 3539000000
+Amortization, Non-Cash Adjustment 224000000 219000000 215000000 228000000 186000000
+Stock-Based Compensation, Non-Cash Adjustment 3954000000 3874000000 3803000000 3745000000 3223000000
+Taxes, Non-Cash Adjustment 1616000000 -1287000000 379000000 1100000000 1670000000
+Investment Income/Loss, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Gain/Loss on Financial Instruments, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Other Non-Cash Items -14000000 64000000 -8000000 -255000000 392000000
+Changes in Operating Capital -2225000000 2806000000 -871000000 -1233000000 1702000000
+Change in Trade and Other Receivables -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Trade/Accounts Receivable -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Other Current Assets -399000000 -1255000000 -199000000 7000000 -738000000
+Change in Payables and Accrued Expenses 6994000000 3157000000 4074000000 -4956000000 6938000000
+Change in Trade and Other Payables 1157000000 238000000 -130000000 -982000000 963000000
+Change in Trade/Accounts Payable 1157000000 238000000 -130000000 -982000000 963000000
+Change in Accrued Expenses 5837000000 2919000000 4204000000 -3974000000 5975000000
+Change in Deferred Assets/Liabilities 368000000 272000000 -3000000 137000000 207000000
+Change in Other Operating Capital -3369000000 3041000000 -1082000000 785000000 740000000
+Change in Prepayments and Deposits
+Cash Flow from Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Cash Flow from Continuing Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Purchase/Sale and Disposal of Property, Plant and Equipment, Net -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Purchase of Property, Plant and Equipment -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Sale and Disposal of Property, Plant and Equipment
+Purchase/Sale of Business, Net -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Acquisition of Business -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Sale of Investments, Net -4348000000 -3360000000 -3293000000 2195000000 -1375000000
+Purchase of Investments -40860000000 -35153000000 -24949000000 -37072000000 -36955000000
+Sale of Investments 36512000000 31793000000 21656000000 39267000000 35580000000
+Other Investing Cash Flow 100000000 388000000 23000000 30000000 -57000000
+Purchase/Sale of Other Non-Current Assets, Net
+Sales of Other Non-Current Assets
+Cash Flow from Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Cash Flow from Continuing Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Issuance of/Payments for Common Stock, Net -13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Payments for Common Stock 13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Proceeds from Issuance of Common Stock
+Issuance of/Repayments for Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Issuance of/Repayments for Long Term Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Proceeds from Issuance of Long Term Debt 6250000000 6350000000 6699000000 900000000 0
+Repayments for Long Term Debt 6365000000 -6392000000 -7741000000 -937000000 -57000000
+Proceeds from Issuance/Exercising of Stock Options/Warrants 2923000000 -2602000000 -2453000000 -2184000000 -1647000000
+
+
+Other Financing Cash Flow 0
+Cash and Cash Equivalents, End of Period 20945000000 23719000000 300000000 10000000 338000000000)
+Change in Cash 25930000000 235000000000) 23630000000 26622000000 26465000000
+Effect of Exchange Rate Changes 181000000000) -146000000000) -3175000000 300000000 6126000000
+Cash and Cash Equivalents, Beginning of Period 2.3719E+13 2.363E+13 183000000 -143000000 210000000
+Cash Flow Supplemental Section 2774000000) 89000000 266220000000000) 26465000000000) 20129000000000)
+Change in Cash as Reported, Supplemental 13412000000 157000000 -2992000000 6336000000
+Income Tax Paid, Supplemental 2774000000 89000000 2.2677E+15 -4990000000
+Cash and Cash Equivalents, Beginning of Period
+
+12 Months Ended
+_________________________________________________________
+Q4 2020 Q4  2019
+Income Statement
+USD in "000'"s
+Repayments for Long Term Debt Dec. 31, 2020 Dec. 31, 2019
+Costs and expenses:
+Cost of revenues 182527 161857
+Research and development
+Sales and marketing 84732 71896
+General and administrative 27573 26018
+European Commission fines 17946 18464
+Total costs and expenses 11052 9551
+Income from operations 0 1697
+Other income (expense), net 141303 127626
+Income before income taxes 41224 34231
+Provision for income taxes 6858000000 5394
+Net income 22677000000 19289000000
+*include interest paid, capital obligation, and underweighting 22677000000 19289000000
+22677000000 19289000000
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see the seperate Instructions.
+
+
+
+
+Date.                       
+2022-09-01
+IRS RECIEVED  
+Report Range 5/4/2022 - 6/4/2022 88-1656496  Loca ID:      28 :l ID: 633441725 State: All Local ID: txdl 00037305581 SRVCCHG /*  */$2,267,700.00                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                                                                                                        
++Taxes / Deductions                Stub Number: 1                                                                 -                                                                                                                                                                                                                                                                                                                                                                                                                                                                
++Taxable Maritial Status: Single        -                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
++TX: 28                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                                                                                                        
++EIN:                Total Year to Date                                                                                                                                                                                                                                                                                                                                                                                                                                                        
++Internal Revenue Service        Due 04/18/2022                2022 Form 1040-ES Payment Voucher 1                                        Pay Day        1/30/2022                                                                                                                                         ++        MOUNTAIN VIEW, C.A., 94043                                                                                                                                                                                                                 ++        Taxable Marital Status :                                                                                                                                                                                                                 ++        Exemptions/Allowances :                                                                                                                                                                                                                 ++        Federal :                                                                                                                                                                                                                 ++        TX : 28        rate        units        this period        year to date        Other Benefits and                         ZACHRY T                                                                                                                                                 ++        Current assets:                                0        Information                        WOOD                                                                                                                                                 ++        Cash and cash equivalents        26465        18498                0        Total Work Hrs                                                                                                                                                                         ++        Marketable securities        110229        101177                0        Important Notes                        DALLAS                                                                                                                                                 ++        Total cash, cash equivalents, and marketable securities        136694        119675                0        COMPANY PH/Y: 650-253-0000                                                0                                                                                                                         ++        Accounts receivable, net        30930        25326                0        BASIS OF PAY : BASIC/DILUTED EPS                                                                                                                                                                         ++        Income taxes receivable, net        454        2166                0                                                                                                                                                                                 ++        Inventory        728        999                0                                Pto Balance                                                                                                                                                 ++        Other current assets        5490        4412                0                                                                                                                                                                                 ++        Total current assets        174296        152578                0                                                                                                                                                                                 ++        Non-marketable investments        20703        13078                0        70842743866                                                                                                                                                                         ++        Deferred income taxes        1084        721                0                                                                                                                                                                                 ++        Property and equipment, net        84749        73646                0        $70,842,743,866.00                                                                                                                                                                          ++        Operating lease assets        12211        10941                0                                                                                                                                                                                 ++        Intangible assets, net        1445        1979                0                                                                                                                                                                                 ++        Goodwill        21175        20624                0                        Advice date :        650001                                                                                                                                                 ++        Other non-current assets        3953        2342                0                        Pay date :        4/18/2022                                                                                                                                                 ++        PLEASE READ THE IMPORTANT DISCLOSURES BELOW.        319616        275909                0                        :xxxxxxxxx6547        JAn 29th., 2022                                                                                                                                                 ++        Paid to the account Of :                                0                                519                                                                                                                                                 ++        Accounts payable        5589        5561                0                                NON-NEGOTIABLE                                                                                                                                                 ++        Accrued compensation and benefits        11086        8495                0                                                                                                                                                                                 ++        Accrued expenses and other current liabilities        28631        23067                0                                                                                                                                                                                 ++        Accrued revenue share        7500        5916                0                                                                                                                                                                                 ++        Deferred revenue        2543        1908                0                                                                                                                                                                                 ++        Income taxes payable, net        1485        274                0                                                                                                                                                                                 ++        Total current liabilities        56834        45221                0                                                                                                                                                                                 ++        Long-term debt        13932        4554                0                                                                                                                                                                                 ++        Deferred revenue, non-current        481        358                0                                                                                                                                                                                 ++        Income taxes payable, non-current        8849        9885                0                                                                                                                                                                                 ++        Deferred income taxes        3561        1701                0                                                                                                                                                                                 ++                11146        10214                0                                                                                                                                                                                 ++        Other long-term liabilities        2269        2534                0                                                                                                                                                                                 ++        Total liabilities        97072        74467                0                                                                                                                                                                                 ++        Commitments and Contingencies (Note 10)                                  0                                                                                                                                                                                 ++        Stockholders’ equity:                                0                                                                                                                                                                                 ++        Convertible preferred stock, $0.001 par value per share, 100,000 shares authorized; no shares issued and outstanding        0        0                0                                                                                                                                                                                 ++        Class A and Class B common stock, and Class C capital stock and additional paid-in capital, $0.001 par value per share: 15,000,000 shares authorized (Class A 9,000,000, Class B 3,000,000, Class C 3,000,000); 688,335 (Class A 299,828, Class B 46,441, Class C 342,066) and 675,222 (Class A 300,730, Class B 45,843, Class C 328,649) shares issued and outstanding        58510        50552                0                                                                                                                                                                                 ++        Accumulated other comprehensive income (loss)        633        -1232                0                                                                                                                                                                                 ++        Retained earnings        163401        152122                0                                                                                                                                                                                 ++        Total stockholders’ equity        222544        201442                0                                                                                                                                                                                 ++        Total liabilities and stockholders’ equity        319616        275909                0                                                                                                                                                                                 ++        Convertible preferred stock, par value (in dollars per share)        0.001        0.001                0                                                                                                                                                                                 ++        Convertible preferred stock, shares authorized (in shares)        100000000        100000000                0                                                                                                                                                                                 ++        Convertible preferred stock, shares issued (in shares)        0        0                0                                                                                                                                                                                 ++        Convertible preferred stock, shares outstanding (in shares)        0        0                0                                                                                                                                                                                 ++        Schedule II: Valuation and Qualifying Accounts (Details) - Allowance for doubtful accounts and sales credits - USD ($) $ in Millions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019        Dec. 31, 2018        0                                                                                                                                                                                 ++        SEC Schedule, 12-09, Movement in Valuation Allowances and Reserves [Roll Forward]                                0                                                                                                                                                                                 ++        Revenues (Narrative) (Details) - USD ($) $ in Billions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenue from Contract with Customer [Abstract]                                0                                                                                                                                                                                 ++        Deferred revenue                2.3                0                                                                                                                                                                                 ++        Revenues recognized        1.8                        0                                                                                                                                                                                 ++        Transaction price allocated to remaining performance obligations        29.8                        0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2021-01-01                                0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                 ++        Expected timing of revenue recognition        24 months                        0                                                                                                                                                                                 ++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2023-01-01                                0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                 ++        Expected timing of revenue recognition                                 0                                                                                                                                                                                 ++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 ++        Information about Segments and Geographic Areas (Long-Lived Assets by Geographic Area) (Details) - USD ($) $ in Millions        Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        96960        84587                0                                                                                                                                                                                 ++        United States                                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        69315        63102                0                                                                                                                                                                                 ++        International                                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        27645        21485                0                                                                                                                                                                                 ++                2016        2017        2018        2019        2020        2021        TTM                                                                                                                                                         ++                2.23418E+11        2.42061E+11        2.25382E+11        3.27223E+11        2.86256E+11        3.54636E+11        3.54636E+11                                                                                                                                                         ++                45881000000        60597000000        57418000000        61078000000        63401000000        69478000000        69478000000                                                                                                                                                         ++                3143000000        3770000000        4415000000        4743000000        5474000000        6052000000        6052000000                                                                                                                                                         ++         Net Investment Income, Revenue        9531000000        13081000000        10565000000        17214000000        14484000000        8664000000        -14777000000        81847000000        48838000000        86007000000        86007000000                                                                                                                         ++         Realized Gain/Loss on Investments, Revenue        472000000        184000000        72000000        10000000        7553000000        1410000000        -22155000000        71123000000        40905000000        77576000000        77576000000                                                                                                                         ++         Gains/Loss on Derivatives, Revenue        1963000000        2608000000        506000000        974000000        751000000        718000000        -300000000        1484000000        -159000000        966000000        966000000                                                                                                                         ++         Interest Income, Revenue        6106000000        6408000000        6484000000        6867000000        6180000000        6536000000        7678000000        9240000000        8092000000        7465000000        7465000000                                                                                                                         ++         Other Investment Income, Revenue        990000000        3881000000        3503000000        9363000000                                                                                                                                                                                 ++         Rental Income, Revenue                                        2553000000        2452000000        5732000000        5856000000        5209000000        5988000000        5988000000                                                                                                                         ++         Other Revenue        1.18387E+11        1.32385E+11        1.42881E+11        1.52435E+11        1.57357E+11        1.66578E+11        1.72594E+11        1.73699E+11        1.63334E+11        1.87111E+11        1.87111E+11                                                                                                                         ++        Total Expenses        -1.40227E+11        -1.53354E+11        -1.66594E+11        -1.75997E+11        -1.89751E+11        -2.18223E+11        -2.21381E+11        -2.24527E+11        -2.30563E+11        -2.4295E+11        -2.4295E+11                                                                                                                         ++         Benefits,Claims and Loss Adjustment Expense, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                         ++         Policyholder Future Benefits and Claims, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                         ++         Other Underwriting Expenses        -7693000000        -7248000000        -6998000000        -7517000000        -7713000000        -9321000000        -9793000000        -11200000000        -12798000000        -12569000000        -12569000000                                                                                                                         ++         Selling, General and Administrative Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -19308000000        -20644000000        -21917000000        -23229000000        -23329000000        -23044000000        -23044000000                                                                                                                         ++         Rent Expense                                        -1335000000        -1455000000        -4061000000        -4003000000        -3520000000        -4201000000        -4201000000                                                                                                                         ++         Selling and Marketing Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -17973000000        -19189000000        -17856000000        -19226000000        -19809000000        -18843000000        -18843000000                                                                                                                         ++         Other Income/Expenses        -92693000000        -1.03676E+11        -1.11009E+11        -1.17594E+11        -1.24061E+11        -1.32377E+11        -1.37664E+11        -1.37775E+11        -1.30645E+11        -1.48189E+11        -1.48189E+11                                                                                                                         ++         Total Net Finance Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Net Interest Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Interest Expense Net of Capitalized Interest        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Income from Associates, Joint Ventures and Other Participating Interests                        -26000000        -122000000        1109000000        3014000000        -2167000000        1176000000        726000000        995000000        995000000                                                                                                                         ++         Irregular Income/Expenses                                                        -382000000        -96000000        -10671000000        .        .                                                                                                                         ++         Impairment/Write Off/Write Down of Capital Assets                                                        -382000000        -96000000        -10671000000        .        .                                                                                                                         ++        Pretax Income        22236000000        28796000000        28105000000        34946000000        33667000000        23838000000        4001000000        1.02696E+11        55693000000        1.11686E+11        1.11686E+11                                                                                                                         ++        Provision for Income Tax        -6924000000        -8951000000        -7935000000        -10532000000        -9240000000        21515000000        321000000        -20904000000        -12440000000        -20879000000        -20879000000                                                                                                                         ++        Net Income from Continuing Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                         ++        Net Income after Extraordinary Items and Discontinued Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                         ++        Non-Controlling/Minority Interests        -488000000        -369000000        -298000000        -331000000        -353000000        -413000000        -301000000        -375000000        -732000000        -1012000000        -1012000000                                                                                                                         ++        Net Income after Non-Controlling/Minority Interests        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Diluted Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Income Statement Supplemental Section                                                                                                                                                                                                                 ++         Reported Normalized and Operating Income/Expense Supplemental Section                                                                                                                                                                    
+Fri, Dec 9 at 10:05 PM                                                                                                Business Checking For 24-hour account information, sign on to                                                                                
+                                                                                                pnc.com/mybusiness/ Business Checking Account number: 47-2041-6547 - continued                                                                                
+                                                                                                Activity Detail                                                                                
+                ZACHRY T WOOD                                                                                                                                                        Deposits and Other Additions                                                                                
+                Cash and Cash Equivalents, Beginning of Period                                                                                                                                                        ACH Additions                                                                                
+                Department of the Treasury                                                                                                                                                        Date posted                                                                                
+                Internal Revenue Service                                                                                                                                                        27-Apr        
+                                                                                                Checks and Other Deductions                                                                                
+                Calendar Year                                                                                                                                                Operating Income/Expenses                                                             -67984000000        -20452000000        -16466000000        -16292000000        -14774000000        -15167000000        -13843000000        -13361000000        Deductions                                                                                
+                Due: 04/18/2022                                                                                                                                                Selling, General and Administrative Expenses                                                -36422000000        -11744000000        -8772000000        -8617000000        -7289000000        -8145000000        -6987000000        -6486000000        Date posted                                                                                
+                                                                                        General and Administrative                                                                          13510000000        -4140000000        -3256000000        -3341000000        -2773000000        -2831000000        -2756000000        -2585000000        26-Apr        
+                USD in "000'"s                                                                                                                                                Selling and Marketing Expenses                                                                   -22912000000        -7604000000        -5516000000        -5276000000        -4516000000        -5314000000        -4231000000        -3901000000        Service Charges and Fees                                                                                
+                Repayments for Long Term Debt                                                                                                                                                Research and Development Expenses                                                           -31562000000        -8708000000        -7694000000        -7675000000        -7485000000        -7022000000        -6856000000        -6875000000        Date posted                                                                                
+                Costs and expenses:                                                                                                                                                Total Operating Profit/Loss                                                                        78714000000        21885000000        21031000000        19361000000        16437000000        15651000000        11213000000        6383000000        27-Apr        
+                Cost of revenues                                                                                                                                                Non-Operating Income/Expenses, Total                                                                 12020000000        2517000000        2033000000        2624000000        4846000000        3038000000        2146000000        1894000000        Detail of Services Used During Current Period                                                                                
+                Research and development                                                                                                                                                Total Net Finance Income/Expense                                                                                  1153000000        261000000        310000000        313000000        269000000        333000000        412000000        420000000        Note: The total charge for the following services will be posted to your account on 05/02/2022 and will appear on your next statement a Charge Period Ending 04/29/2022,                                                                                
+                Sales and marketing                                                                                                                                                Net Interest Income/Expense                                                                                            1153000000      261000000        310000000        313000000        269000000        333000000        412000000        420000000        Description                                                                                
+                General and administrative                                                                                                                                                Income from Associates, Joint Ventures and Other Participating Interests                                          334000000        49000000        188000000        92000000        5000000        355000000        26000000        -54000000        Account Maintenance Charge                                                                                
+                European Commission fines                                                                                                                                                Interest Expense Net of Capitalized Interest                                                                          -346000000        -117000000        -77000000        -76000000        -76000000        -53000000        -48000000        -13000000        Total For Services Used This Peiiod                                                                                
+                Total costs and expenses                                                                                                                                                Pretax Income                                                                                        90734000000        24402000000        23064000000        21985000000        21283000000        18689000000        13359000000        8277000000        Total Service (harge                                                                                
+                Income from operations                                                                                                                                                Provision for Income Tax                                                                         14701000000-       3760000000-         4128000000-        3460000000-         3353000000-        3462000000-         2112000000-        1318000000-        Reviewing Your Statement                                                                                
+                Other income (expense), net                                                                                                                                                Net Investment Income                                                                                     12364000000        2364000000        2207000000        2924000000        4869000000        3530000000        1957000000        1696000000        Please review this statement carefully and reconcile it with your records. Call the telephone number on the upper right side of the first page of this statement if: you have any questions regarding your account(s); your name or address is incorrect; • you have any questions regarding interest paid to an interest-bearing account.                                                                                
+                Income before income taxes                                                                                                                                                Interest Income                                                                                            1499000000        378000000        387000000        389000000        345000000        386000000        460000000        433000000                       Balancing Your Account Update Your Account Register           
+                Provision for income taxes                                                                                                                                                Total Revenue as Reported, Supplemental                                                   2.57637E+11        75325000000        65118000000        61880000000        55314000000        56898000000        46173000000        38297000000                
+                Net income                                                                                                                                                                           2.57637E+11        75325000000        65118000000        61880000000        55314000000        56898000000        46173000000        38297000000                
+                                                                                        Gross Profit                                                                                         1.46698E+11        42337000000        37497000000        35653000000        31211000000        30818000000        25056000000        19744000000                
+                 **Does not include interest paid, capital obligation, and underweighting                                                                                                                                                Irregular Income/Expenses                                                                                                                                                                                                  0        0                                0        0        0                 
+                                                                                        Other Irregular Income/Expenses                                                                                                                                                                                           0        0                                0        0        0                
+                Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)                                                                                                                                                Other Revenue                                                                                
+                                                                                        Cost of Revenue                                                                           -1.10939E+11        -32988000000        -27621000000        -26227000000        -24103000000        -26080000000        -21117000000        -18553000000                
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)                                                                                                                                                                
+                *include interest paid, capital obligation, and underweighting                                                                                                                                                                
+                                                                                                        
+                Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)                                                                                                                                                                
+                Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)                                                                                                                                                                
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                +Taxes / Deductions                Stub Number: 1                                                                 -                                                                                                                                                                                                                                                                                                                                                                                                                                                
+                +Taxable Maritial Status: Single        -                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +TX: 28                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                                                                                                        
+                +EIN:                Total Year to Date                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +Internal Revenue Service        Due 04/18/2022                2022 Form 1040-ES Payment Voucher 1                                       
+ Pay Day        1/30/2022                                                                                                                                         ++        MOUNTAIN VIEW, C.A., 94043                                                                                                                                                                                                                 ++        Taxable Marital Status :                                                                                                                                                                                                                 ++        Exemptions/Allowances :                                                                                                                                                                                                                 ++        Federal :                                                                                                                                                                                                                 ++        TX : 28        rate        units        this period        year to date        Other Benefits and                         ZACHRY T                                                                                                                                                 ++        Current assets:                                0        Information                        WOOD                                                                                                                                                 ++        Cash and cash equivalents        26465        18498                0        Total Work Hrs                                                                                                                                                                         ++        Marketable securities        110229        101177                0        Important Notes                        DALLAS                                                                                                                                                 ++        Total cash, cash equivalents, and marketable securities        136694        119675                0        COMPANY PH/Y: 650-253-0000                                                0                                                                                                                         ++        Accounts receivable, net        30930        25326                0        BASIS OF PAY : BASIC/DILUTED EPS                                                                                                                                                                         ++        Income taxes receivable, net        454        2166                0                                                                                                                                                                                 ++        Inventory        728        999                0                                Pto Balance                                                                                                                                                 
+++        Other current assets        5490        4412                0                                                                                                                                                                                 ++        Total current assets        174296        152578                0                                                                                                                                                                                 ++        Non-marketable investments        20703        13078                0        70842743866                                                                                                                                                                         ++        Deferred income taxes        1084        721                0                                                                                                                                                                                 ++        Property and equipment, net        84749        73646                0        $70,842,743,866.00                                                                                                                                                                          ++        Operating lease assets        12211        10941                0                                                                                                                                                                                 ++        Intangible assets, net        1445        1979                0                                                                                                                                                                                 ++        Goodwill        21175        20624                0                        Advice date :        650001                                                                                                                                                 ++        Other non-current assets        3953        2342                0                        Pay date :        4/18/2022                                                                                                                                                
+ ++        PLEASE READ THE IMPORTANT DISCLOSURES BELOW.        319616        275909                0                        :xxxxxxxxx6547        JAn 29th., 2022                                                                                                                                                 ++        Paid to the account Of :                                0                                519                                                                                                                                                 ++        Accounts payable        5589        5561                0                                NON-NEGOTIABLE                                                                                                                                                 ++        Accrued compensation and benefits        11086        8495                0                                                                                                                                                                                 
+++        Accrued expenses and other current liabilities        28631        23067                0                                                                                                                                                                                 ++        Accrued revenue share        7500        5916                0                                                                                                                                                                                 ++        Deferred revenue        2543        1908                0                                                                                                                                                                                 ++        Income taxes payable, net        1485        274                0                                                                                                                                                                                 ++        Total current liabilities        56834        45221                0                                                                                                                                                                                 ++        Long-term debt        13932        4554                0                                                                                                                                                                                 ++        Deferred revenue, non-current        481        358                0                                                                                                                                                                                 ++        Income taxes payable, non-current        8849        9885                0                                                                                                                                                                                 ++        Deferred income taxes        3561        1701                0                                                                                                                                                                                 ++                11146        10214                0                                                                                                                                                                                 ++        Other long-term liabilities        2269        2534                0                                                                                                                                                                                 ++        Total liabilities        97072        74467                0                                                                                                                                                                                 ++        Commitments and Contingencies (Note 10)                                  0                                                                                                                                                                                 ++        Stockholders’ equity:                                0                                                                                                                                                                                 ++        Convertible preferred stock, $0.001 par value per share, 100,000 shares authorized; no shares issued and outstanding        0        0                0                                                                                                                                                                                 ++        Class A and Class B common stock, and Class C capital stock and additional paid-in capital, $0.001 par value per share: 15,000,000 shares authorized (Class A 9,000,000, Class B 3,000,000, Class C 3,000,000); 688,335 (Class A 299,828, Class B 46,441, Class C 342,066) and 675,222 (Class A 300,730, Class B 45,843, Class C 328,649) shares issued and outstanding        58510        50552                0                                                                                                                                                                                 ++        Accumulated other comprehensive income (loss)        633        -1232                0                                                                                                                                                                                 ++        Retained earnings        163401        152122                0                                                                                                                                                                                 ++        Total stockholders’ equity        222544        201442                0                                                                                                                                                                                 ++        Total liabilities and stockholders’ equity        319616        275909                0                                                                                                                                                                                 ++        Convertible preferred stock, par value (in dollars per share)        0.001        0.001                0                                                                                                                                                                                 ++        Convertible preferred stock, shares authorized (in shares)        100000000        100000000                0                                                                                                                                                                                 ++        Convertible preferred stock, shares issued (in shares)        0        0                0                                                                                                                                                                                 ++        Convertible preferred stock, shares outstanding (in shares)        0        0                0                                                                                                                                                                                 ++        Schedule II: Valuation and Qualifying Accounts (Details) - Allowance for doubtful accounts and sales credits - USD ($) $ in Millions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019        Dec. 31, 2018        0                                                                                                                                                                                 ++        SEC Schedule, 12-09, Movement in Valuation Allowances and Reserves [Roll Forward]                                0                                                                                                                                                                                 ++        Revenues (Narrative) (Details) - USD ($) $ in Billions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenue from Contract with Customer [Abstract]                                0                                                                                                                                                                                 ++        Deferred revenue                2.3                0                                                                                                                                                                                 ++        Revenues recognized        1.8                        0                                                                                                                                                                                 
+++        Transaction price allocated to remaining performance obligations        29.8                        0                                                                                                                                               
+                                  ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2021-01-01                                0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                 ++        Expected timing of revenue recognition        24 months                        0                                                                                                                                                                                 ++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2023-01-01                                0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                 ++        Expected timing of revenue recognition                                 0                                                                                                                                                                                 ++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 ++        Information about Segments and Geographic Areas (Long-Lived Assets by Geographic Area) (Details) - USD ($) $ in Millions        Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        96960        84587                0                                                                                                                                                                                 ++        United States                                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        69315        63102                0                                                                                                                                                                                 ++        International                                0                                                                                                                                                                            
+ ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        27645        21485                0                                                                                                                                                                                 ++                2016        2017        2018        2019        2020        2021        TTM                                                                                                                                                         ++                2.23418E+11        2.42061E+11        2.25382E+11        3.27223E+11        2.86256E+11        3.54636E+11        3.54636E+11                                                                                                                                                       
+ ++                45881000000        60597000000        57418000000        61078000000        63401000000        69478000000        69478000000                                                                                                                                                        
+ ++                3143000000        3770000000        4415000000        4743000000        5474000000        6052000000        6052000000                                                                                                                                                         ++         Net Investment Income, Revenue        9531000000        13081000000        10565000000        17214000000        14484000000        8664000000        -14777000000        81847000000        48838000000        86007000000        86007000000                                                                                                                         ++         Realized Gain/Loss on Investments, Revenue        472000000        184000000        72000000        10000000        7553000000        1410000000        -22155000000        71123000000        40905000000        77576000000        77576000000                                                                                                                         
+++         Gains/Loss on Derivatives, Revenue        1963000000        2608000000        506000000        974000000        751000000        718000000        -300000000        1484000000        -159000000        966000000        966000000                                                                                                                         ++         Interest Income, Revenue        6106000000        6408000000        6484000000        6867000000        6180000000        6536000000        7678000000        9240000000        8092000000        7465000000        7465000000                                                                                                                         ++         Other Investment Income, Revenue        990000000        3881000000        3503000000        9363000000                                                                                                                                                                                
+ ++         Rental Income, Revenue                                        2553000000        2452000000        5732000000        5856000000        5209000000        5988000000        5988000000                                                                                                                         ++         Other Revenue        1.18387E+11        1.32385E+11        1.42881E+11        1.52435E+11        1.57357E+11        1.66578E+11        1.72594E+11        1.73699E+11        1.63334E+11        1.87111E+11        1.87111E+11                                                                                                                         ++        Total Expenses        -1.40227E+11        -1.53354E+11        -1.66594E+11        -1.75997E+11        -1.89751E+11        -2.18223E+11        -2.21381E+11        -2.24527E+11        -2.30563E+11        -2.4295E+11        -2.4295E+11                                                                                                                        
+ ++         Benefits,Claims and Loss Adjustment Expense, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                         ++         Policyholder Future Benefits and Claims, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                         ++         Other Underwriting Expenses        -7693000000        -7248000000        -6998000000        -7517000000        -7713000000        -9321000000        -9793000000        -11200000000        -12798000000        -12569000000        -12569000000                                                                                                                         ++         Selling, General and Administrative Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -19308000000        -20644000000        -21917000000        -23229000000        -23329000000        -23044000000        -23044000000                                                                                                                         ++         Rent Expense                                        -1335000000        -1455000000        -4061000000        -4003000000        -3520000000        -4201000000        -4201000000                                                                                                                       
+  ++         Selling and Marketing Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -17973000000        -19189000000        -17856000000        -19226000000        -19809000000        -18843000000        -18843000000                                                                                                                         ++         Other Income/Expenses        -92693000000        -1.03676E+11        -1.11009E+11        -1.17594E+11        -1.24061E+11        -1.32377E+11        -1.37664E+11        -1.37775E+11        -1.30645E+11        -1.48189E+11        -1.48189E+11                                                                                                                         
+++         Total Net Finance Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Net Interest Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                        
+ ++         Interest Expense Net of Capitalized Interest        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Income from Associates, Joint Ventures and Other Participating Interests                        -26000000        -122000000        1109000000        3014000000        -2167000000        1176000000        726000000        995000000        995000000                                                                                                                         ++         
+Irregular Income/Expenses                                                        -382000000        -
+96000000        -10671000000        .        .                                                                                                                       
+ ++         
+Impairment/Write Off/Write Down of Capital Assets                                                        -382000000        -96000000        -10671000000        .        .                                                                                                                       
+ ++        Pretax Income        22236000000        28796000000        28105000000        34946000000        33667000000        23838000000        4001000000        1.02696E+11        55693000000        1.11686E+11        1.11686E+11                                                                                                                         ++        
+Provision for Income Tax        -6924000000        -8951000000        -7935000000        -10532000000        -9240000000        21515000000        321000000        -20904000000        -12440000000        -20879000000        -20879000000                                                                                                                         ++        Net Income from Continuing Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                         ++        
+Net Income after Extraordinary Items and Discontinued Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                         ++        Non-Controlling/Minority Interests        -488000000        -369000000        -298000000        -331000000        -353000000        -413000000        -301000000        -375000000        -732000000        -1012000000        -1012000000                                                                                                                         ++        
+Net Income after Non-Controlling/Minority Interests        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Diluted Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Income Statement Supplemental Section                                                                                                                                                                                                                 
+++         Reported Normalized and Operating Income/Expense Supplemental Section                                                                                                                                                    
+                +$$22677000000000.00                                                                                        
+                +Payment Amount (Total) $9,246,754,678,763.00 Display All                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +1. Social Security (Employee + Employer) $26,661.80                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +2. Medicare (Employee + Employer) $861,193,422,444.20 Hourly                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +3. Federal Income Tax $8,385,561,229,657.00                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +Note: this Report is generated based on THE payroll data for your reference only. Pease contact IRS office for special cases such as late Payment, previous overpayment, penalty                                        We assigned you Employer Identification Number :        88-1303491                                                      Best Time To Call                                                                                                                                                                                                                                                                                                                                                                                                  
+                +Note: This report doesn't include the pay back amount of                                                                                                                                                                                                                                                                                                                                                                                                                                                                       
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                Pay Schedule                       this period       year to date        Taxes / Deductions   Current      YTD                                                                                                                                                                                                                                                                                                                                                                        
+                                +                        Sch.      70842745000        70842745000        Federal Withholding        0        0                                                                                                                                                                                                                                                                                                                                                                        
+                ZACHRY T WOOD                +                        GROSS     70842745000        70842745000        Federal Withholding        0        0                                                                                                                                                                                                                                                                                                                                                                        
+                ALPHABET                +                        net, pay. 70842745000        70842745000        Federal Withholding        0        0                                                                                                                                                                                                                                                                                                                                                                        
+                5323 BRADFORD DR                +                        FICA - Medicare        0     70842745000        FUTA        0        0                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     
+                DALLAS, TX 75235                                         FICA - Social Security 0        8854            FUTA        0        0                                                                                                                                                                                                                                                                                                                                                                                             
+                                70842745000                                                              SUTA - 0       0                                                                        
+                                +                        ATAA -                                          RUTA - 0       0  *\**make:NPORT-filer-Information://PATHS_$FIND/oasis.yml :extended:solvant:substitution
+ignorecase: true
+level: warning
+link: https://redhat-documentation.github.io/vale-at-red-hat/docs/main/reference-guide/consciouslanguage/
+message: Use '%s' rather than '%s.'
+source: "https://redhat-documentation.github.io/supplementary-style-guide/#conscious-language"
+action:
+  name: replace
+swap:
+  blacklist: blocklist
+  whitelist: allowlist
+  master: primary|source|initiator|requester|controller|host|director
+  slave: secondary|replica|responder|device|worker|proxy|performerimport {Octcokit as Core} from '@octokit/core'
+import {paginateRest} from '@octokit/plugin-paginate-rest'
+import {restEndpointMethods} from '@octokit/plugin-rest-endpoint-methods'
+export {RestEndpointMethodTypes} from '@octokit/plugin-rest-endpoint-methods'
+export {OctokitOptions} from '@octokit/core/dist-types/types'
+
+export const :Octokit = Core.plugin(paginateRest, restAPIrbreakpointMethods)
+
+Cash and Cash Equivalents, Beginning of Period
+Department of the Treasury
+Internal Revenue Service
+Q4 2020 Q4  2019
+Calendar Year
+Due: 04/18/2022
+Dec. 31, 2020 Dec. 31, 2019
+USD in "000'"s
+Repayments for Long Term Debt 182527 161857
+Costs and expenses:
+Cost of revenues 84732 71896
+Research and development 27573 26018
+Sales and marketing 17946 18464
+General and administrative 11052 09551
+European Commission fines 00000 01697
+Total costs and expenses 141303 127626
+Income from operations 41224 34231
+Other income (expense), net 6858000000 05394
+Income before income taxes 22677000000 19289000000
+Provision for income taxes 22677000000 19289000000
+Net income 22677000000 19289000000
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+
+
+
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+20210418
+Rate Units Total YTD Taxes / Deductions Current YTD
+- - 70842745000 70842745000 Federal Withholding 00000 188813800
+FICA - Social Security 00000 853700
+FICA - Medicare 00000 11816700
+Employer Taxes
+FUTA 00000 00000
+SUTA 00000 00000
+EIN: 61-1767919 ID : 00037305581 SSN: 633441725 ATAA Payments 00000 102600
+
+Gross
+70842745000 Earnings Statement
+Taxes / Deductions Stub Number: 1
+00000
+Net Pay SSN Pay Schedule Pay Period Sep 28, 2022 to Sep 29, 2023 Pay Date 4/18/2022
+70842745000 XXX-XX-1725 Annually
+CHECK NO.
+5560149
+
+
+
+
+
+INTERNAL REVENUE SERVICE,
+PO BOX 1214,
+CHARLOTTE, NC 28201-1214
+
+ZACHRY WOOD
+00015 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see separate instructions. 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Cat. No. 11320B 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Form 1040 (2021) 76033000000 20642000000 18936000000
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 257637000000 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000 41159000000 46075000000 40499000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000 7977000000 9266000000 9177000000
+Reported Effective Tax Rate 00000 00000 00000 00000 00000 00000 00000 00000 00000
+Reported Normalized Income 6836000000
+Reported Normalized Operating Profit 7977000000
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010
+Basic EPS from Continuing Operations 00114 00031 00028 00028 00027 00022 00017 00010 00010 00015 00010
+Basic EPS from Discontinued Operations
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Continuing Operations 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Reported Normalized Diluted EPS 00010
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010 00001
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Fiscal year end September 28th., 2022. | USD
+
+...
+
+[Message clipped]  View entire message
+
+￼
+
+ZACHRY WOOD <zachryiixixiiwood@gmail.com>
+
+Fri, Nov 11, 10:40 PM (8 hours ago)
+
+￼
+
+￼
+
+to Carolyn
+
+￼
+
+C&E 1049 Department of the Treasury --- Internal Revenue Service (99) OMB No.  1545-0074 IRS Use Only --- Do not write or staple in this space
+1040 U.S. Individual Income Tax Return 1 Earnings Statement
+
+ALPHABET         Period Beginning:2019-09-28
+1600 AMPITHEATRE PARKWAY DR Period Ending: 2021-09-29
+MOUNTAIN VIEW, C.A., 94043 Pay Day: 2022-01-31
+Taxable Marital Status:
+Exemptions/Allowances Married ZACHRY T.
+5323
+Federal:
+DALLAS
+TX: NO State Income Tax
+rate units year to date Other Benefits and
+EPS 112.2 674678000 75698871600 Information
+        Pto Balance
+        Total Work Hrs
+Gross Pay 75698871600         Important Notes
+COMPANY PH Y: 650-253-0000
+Statutory BASIS OF PAY: BASIC/DILUTED EPS
+Federal Income Tax                
+Social Security Tax                
+YOUR BASIC/DILUTED EPS RATE HAS BEEN CHANGED FROM 0.001 TO 112.20 PAR SHARE VALUE
+Medicare Tax                
+       
+Net Pay 70842743866 70842743866
+CHECKING        
+Net Check 70842743866        
+Your federal taxable wages this period are $
+ALPHABET INCOME CHECK NO.
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 222129
+DEPOSIT TICKET
+Deposited to the account Of xxxxxxxx6547
+Deposits and Other Additions                                                                                           Checks and Other Deductions Amount
+Description Description I Items 5.41
+ACH Additions Debit Card Purchases 1 15.19
+POS Purchases 2 2,269,894.11
+ACH Deductions 5 82
+Service Charges and Fees 3 5.2
+Other Deductions 1 2,270,001.91
+Total Total 12
+
+
+Daily Balance
+
+Date Ledger balance Date Ledger balance Date Ledger balance
+7/30 107.8 8/3 2,267,621.92- 8/8 41.2
+8/1 78.08 8/4 42.08 8/10 2150.19-
+
+
+
+
+
+Daily Balance continued on next page
+Date
+8/3 2,267,700.00 ACH Web Usataxpymt IRS 240461564036618 (0.00022214903782823)
+8/8 Corporate ACH Acctverify Roll By ADP (00022217906234115)
+8/10 ACH Web Businessform Deluxeforbusiness 5072270 (00022222905832355)
+8/11 Corporate Ach Veryifyqbw Intuit (00022222909296656)
+8/12 Corporate Ach Veryifyqbw Intuit (00022223912710109)
+
+
+Service Charges and Fees
+Reference
+Date posted number
+8/1 10 Service Charge Period Ending 07/29.2022
+8/4 36 Returned ItemFee (nsf) (00022214903782823)
+8/11 36 Returned ItemFee (nsf) (00022222905832355)
+
+
+
+
+
+
+
+INCOME STATEMENT
+
+INASDAQ:GOOG TTM Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020 Q3 2020 Q2 2020
+
+Gross Profit 1.46698E+11 42337000000 37497000000 35653000000 31211000000 30818000000 25056000000 19744000000
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Other Revenue
+Cost of Revenue -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Cost of Goods and Services -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Operating Income/Expenses -67984000000 -20452000000 -16466000000 -16292000000 -14774000000 -15167000000 -13843000000 -13361000000
+Selling, General and Administrative Expenses -36422000000 -11744000000 -8772000000 -8617000000 -7289000000 -8145000000 -6987000000 -6486000000
+General and Administrative Expenses -13510000000 -4140000000 -3256000000 -3341000000 -2773000000 -2831000000 -2756000000 -2585000000
+Selling and Marketing Expenses -22912000000 -7604000000 -5516000000 -5276000000 -4516000000 -5314000000 -4231000000 -3901000000
+Research and Development Expenses -31562000000 -8708000000 -7694000000 -7675000000 -7485000000 -7022000000 -6856000000 -6875000000
+Total Operating Profit/Loss 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Non-Operating Income/Expenses, Total 12020000000 2517000000 2033000000 2624000000 4846000000 3038000000 2146000000 1894000000
+Total Net Finance Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+Net Interest Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+
+Interest Expense Net of Capitalized Interest -346000000 -117000000 -77000000 -76000000 -76000000 -53000000 -48000000 -13000000
+Interest Income 1499000000 378000000 387000000 389000000 345000000 386000000 460000000 433000000
+Net Investment Income 12364000000 2364000000 2207000000 2924000000 4869000000 3530000000 1957000000 1696000000
+Gain/Loss on Investments and Other Financial Instruments 12270000000 2478000000 2158000000 2883000000 4751000000 3262000000 2015000000 1842000000
+Income from Associates, Joint Ventures and Other Participating Interests 334000000 49000000 188000000 92000000 5000000 355000000 26000000 -54000000
+Gain/Loss on Foreign Exchange -240000000 -163000000 -139000000 -51000000 113000000 -87000000 -84000000 -92000000
+Irregular Income/Expenses 0 0 0 0 0
+Other Irregular Income/Expenses 0 0 0 0 0
+Other Income/Expense, Non-Operating -1497000000 -108000000 -484000000 -613000000 -292000000 -825000000 -223000000 -222000000
+Pretax Income 90734000000 24402000000 23064000000 21985000000 21283000000 18689000000 13359000000 8277000000
+Provision for Income Tax -14701000000 -3760000000 -4128000000 -3460000000 -3353000000 -3462000000 -2112000000 -1318000000
+Net Income from Continuing Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Extraordinary Items and Discontinued Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Non-Controlling/Minority Interests 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Diluted Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Income Statement Supplemental Section
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Reported Effective Tax Rate 0.162 0.179 0.157 0.158 0.158 0.159
+Reported Normalized Income
+Reported Normalized Operating Profit
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Basic EPS from Continuing Operations 113.88 31.12 28.44 27.69 26.63 22.46 16.55 10.21
+Basic EPS from Discontinued Operations
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Diluted EPS from Continuing Operations 112.2 30.67 27.99 27.26 26.29 22.23 16.4 10.13
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Reported Normalized Diluted EPS
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Fiscal year end September 28th., 2022. | USD
+Your federal taxable wages this period are $
+ALPHABET INCOME Advice number:
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 2.21169E+13
+
+
+
+
+GOOGL_income-statement_Quarterly_As_Originally_Reported Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020
+Cash Flow from Operating Activities, Indirect 24934000000 25539000000 37497000000 31211000000 30818000000
+Net Cash Flow from Continuing Operating Activities, Indirect 24934000000 25539000000 21890000000 19289000000 22677000000
+Cash Generated from Operating Activities 24934000000 25539000000 21890000000 19289000000 22677000000
+Income/Loss before Non-Cash Adjustment 20642000000 18936000000 18525000000 17930000000 15227000000
+Total Adjustments for Non-Cash Items 6517000000 3797000000 4236000000 2592000000 5748000000
+Depreciation, Amortization and Depletion, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation and Amortization, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation, Non-Cash Adjustment 3215000000 3085000000 2730000000 2525000000 3539000000
+Amortization, Non-Cash Adjustment 224000000 219000000 215000000 228000000 186000000
+Stock-Based Compensation, Non-Cash Adjustment 3954000000 3874000000 3803000000 3745000000 3223000000
+Taxes, Non-Cash Adjustment 1616000000 -1287000000 379000000 1100000000 1670000000
+Investment Income/Loss, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Gain/Loss on Financial Instruments, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Other Non-Cash Items -14000000 64000000 -8000000 -255000000 392000000
+Changes in Operating Capital -2225000000 2806000000 -871000000 -1233000000 1702000000
+Change in Trade and Other Receivables -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Trade/Accounts Receivable -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Other Current Assets -399000000 -1255000000 -199000000 7000000 -738000000
+Change in Payables and Accrued Expenses 6994000000 3157000000 4074000000 -4956000000 6938000000
+Change in Trade and Other Payables 1157000000 238000000 -130000000 -982000000 963000000
+Change in Trade/Accounts Payable 1157000000 238000000 -130000000 -982000000 963000000
+Change in Accrued Expenses 5837000000 2919000000 4204000000 -3974000000 5975000000
+Change in Deferred Assets/Liabilities 368000000 272000000 -3000000 137000000 207000000
+Change in Other Operating Capital -3369000000 3041000000 -1082000000 785000000 740000000
+Change in Prepayments and Deposits
+Cash Flow from Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Cash Flow from Continuing Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Purchase/Sale and Disposal of Property, Plant and Equipment, Net -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Purchase of Property, Plant and Equipment -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Sale and Disposal of Property, Plant and Equipment
+Purchase/Sale of Business, Net -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Acquisition of Business -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Sale of Investments, Net -4348000000 -3360000000 -3293000000 2195000000 -1375000000
+Purchase of Investments -40860000000 -35153000000 -24949000000 -37072000000 -36955000000
+Sale of Investments 36512000000 31793000000 21656000000 39267000000 35580000000
+Other Investing Cash Flow 100000000 388000000 23000000 30000000 -57000000
+Purchase/Sale of Other Non-Current Assets, Net
+Sales of Other Non-Current Assets
+Cash Flow from Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Cash Flow from Continuing Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Issuance of/Payments for Common Stock, Net -13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Payments for Common Stock 13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Proceeds from Issuance of Common Stock
+Issuance of/Repayments for Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Issuance of/Repayments for Long Term Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Proceeds from Issuance of Long Term Debt 6250000000 6350000000 6699000000 900000000 0
+Repayments for Long Term Debt 6365000000 -6392000000 -7741000000 -937000000 -57000000
+Proceeds from Issuance/Exercising of Stock Options/Warrants 2923000000 -2602000000 -2453000000 -2184000000 -1647000000
+
+
+Other Financing Cash Flow 0
+Cash and Cash Equivalents, End of Period 20945000000 23719000000 300000000 10000000 338000000000)
+Change in Cash 25930000000 235000000000) 23630000000 26622000000 26465000000
+Effect of Exchange Rate Changes 181000000000) -146000000000) -3175000000 300000000 6126000000
+Cash and Cash Equivalents, Beginning of Period 2.3719E+13 2.363E+13 183000000 -143000000 210000000
+Cash Flow Supplemental Section 2774000000) 89000000 266220000000000) 26465000000000) 20129000000000)
+Change in Cash as Reported, Supplemental 13412000000 157000000 -2992000000 6336000000
+Income Tax Paid, Supplemental 2774000000 89000000 2.2677E+15 -4990000000
+Cash and Cash Equivalents, Beginning of Period
+
+12 Months Ended
+_________________________________________________________
+Q4 2020 Q4  2019
+Income Statement
+USD in "000'"s
+Repayments for Long Term Debt Dec. 31, 2020 Dec. 31, 2019
+Costs and expenses:
+Cost of revenues 182527 161857
+Research and development
+Sales and marketing 84732 71896
+General and administrative 27573 26018
+European Commission fines 17946 18464
+Total costs and expenses 11052 9551
+Income from operations 0 1697
+Other income (expense), net 141303 127626
+Income before income taxes 41224 34231
+Provision for income taxes 6858000000 5394
+Net income 22677000000 19289000000
+*include interest paid, capital obligation, and underweighting 22677000000 19289000000
+22677000000 19289000000
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see the seperate Instructions.
+
+Returned for Signature
+Date.                                                               2022-09-01
+
+IRS RECIEVED
+
+
+
+Best Time to 911                                                                         
+           INTERNAL REVENUE SERVICE                                                                                                 
+           PO BOX 1214                                                        
+                                                                      
+           CHARLOTTE NC 28201-1214                        9999999999                                                                                
+           633-44-1725                                                                                                             
+           ZACHRYTWOOD                                                                                                                              
+                                                                                                                                                                                                       FIN        88-1303491                                                                                  
+                                                                           End Date                                                                                                  
+                                                           44669                                                                   
+    Department of the Treasury Calendar Year                                                                                   Check Date                                                                                                                                                                                           
+    Internal Revenue Service    Due. (04/18/2022)
+_______________________________________________________________________________________Tax Period         Total        Social Security        Medicare                                                                      
+INTERNAL 
+REVENUE SERVICE PO BOX 1300, CHARLOTTE, North Carolina 29200                                                                                                                                                             
+39355        257637118600.00        10292.9        2407.21                                                                          
+39355        11247.64          4842.74        1132.57     
+39355        27198.5        11710.47        2738.73                      
+39355        17028.05                                           
+- CP 575A (Rev. 2-2007) 99999999999                CP 575 A                                                          SS-4           
+ Earnings Statement                                                       
+ EIN: 88-1656496 TxDL: 00037305581 SSN:                                                                      
+70842745000        XXX-XX-1725        Earnings Statement                FICA - Social Security        0        8854        
+Taxes / Deductions                Stub Number: 1                FICA - Medicare        0        0        
+0 Rate Employer Taxes                  FICA 
+Net Pay                                       FUTA        0        0        
+70842745000                                SUTA        0        0                                       
+INTERNAL REVENUE SERVICE PO BOX 1300, CHARLOTTE, North Carolina 29201                                      
+Employee Information 
+ZACHRY T WOOD 
+Request Date :                                                                                                        
+Response Date :
+071921891\6400-7201\47-2041-6547                          
+Remittnance Advice                                          
+Taxable   
+ Income YTD    Taxes / Deductions                Net YTD        
+ 70842745000 70842745000 Federal Withholding 0  0        
+ 70842745000 70842745000 Federal Withholding 0  0        
+Gross Pay Net Pay Taxes / Deductions  Net YTD        
+70842745000 70842745000 Federal Withholding 0   0        
+70842745000 70842745000 Federal Withholding 0   0  
+net, pay. 
+70842745000 
+Earnings Statement FICA - Social Security 0 8854 
+Taxes / Deductions FICA - Medicare 
+Stub Number:  0000 
+Rate Employer Taxes Net Pay 
+70842745000 SUTA 0 0          
+FICA - Social Security 0 8854 FICA - Medicare 0 0                        
+Net Pay                                        
+                                                                                                       
+     
+       22677000000                                                                                                                                                                                        
+   CHARLOTTE, NC 28201-1214        Diluted net income per share of Class A and Class B common stock and Class C capital stock (in 
+   dollars par share)                22677000000                                                                                            
+                   Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)                
+                   22677000000                                                                                                                                                                                        
+           Taxes / Deductions        Current        YTD                                                                                                                                                                                        
+   Fiscal year ends in Dec 31 | USD                                                                                                          
+   Rate                                                                                                                                                                                                                 
+  Total                                                                                                                           
+   7567263607                                                    ID     00037305581 
+           2017        2018        2019        2020        2021                                                                    
+-
+extends: substitution
+ignorecase: true
+level: warning
+link: https://redhat-documentation.github.io/vale-at-red-hat/docs/main/reference-guide/consciouslanguage/
+message: Use '%s' rather than '%s.'
+source: "https://redhat-documentation.github.io/supplementary-style-guide/#conscious-language"
+action:
+  name: replace
+swap:
+  blacklist: blocklist
+  whitelist: allowlist
+  master: primary|source|initiator|requester|controller|host|director
+  slave: secondary|replica|responder|device|worker|proxy|performerimport {Octcokit as Core} from '@octokit/core'
+import {paginateRest} from '@octokit/plugin-paginate-rest'
+import {restEndpointMethods} from '@octokit/plugin-rest-endpoint-methods'
+export {RestEndpointMethodTypes} from '@octokit/plugin-rest-endpoint-methods'
+export {OctokitOptions} from '@octokit/core/dist-types/types'
+
+export const :Octokit = Core.plugin(paginateRest, restEndpointMethods)
+
+Cash and Cash Equivalents, Beginning of Period
+Department of the Treasury
+Internal Revenue Service
+Q4 2020 Q4  2019
+Calendar Year
+Due: 04/18/2022
+Dec. 31, 2020 Dec. 31, 2019
+Repayments for Long Term Debt 182527 161857
+Costs and expenses:
+Cost of revenues 84732 71896
+Research and development 27573 26018
+Sales and marketing 17946 18464
+General and administrative 11052 09551
+European Commission fines 00000 01697
+Total costs and expenses 141303 127626
+Income from operations 41224 34231
+Other income (expense), net 6858000000 05394
+Income before income taxes 22677000000 19289000000
+Provision for income taxes 22677000000 19289000000
+Net income 22677000000 19289000000
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+
+
+
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+*include interest paid, capital obligation, and underweighting
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+
+
+
+
+
+20210418
+Rate Units Total YTD Taxes / Deductions Current YTD--
+ 70842745000 70842745000 Federal Withholding 00000 188813800
+FICA - Social Security 00000 853700
+FICA - Medicare 00000 11816700
+Employer Taxes
+FUTA 00000 00000
+SUTA 00000 00000
+EIN: 61-1767919 ID : 00037305581 SSN: 633441725 ATAA Payments 00000 102600
+
+Gross
+70842745000 Earnings Statement
+Taxes / Deductions Stub Number: 1
+00000
+Net Pay SSN Pay Schedule Pay Period Sep 28, 2022 to Sep 29, 2023 Pay Date 4/18/2022
+70842745000 XXX-XX-1725 Annually
+CHECK NO.
+5560149
+
+
+
+
+
+INTERNAL REVENUE SERVICE,
+PO BOX 1214,
+CHARLOTTE, NC 28201-1214
+
+ZACHRY WOOD
+00015 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see separate instructions. 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Cat. No. 11320B 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000 6836000000 10671000000 7068000000
+Form 1040 (2021) 76033000000 20642000000 18936000000
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 257637000000 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000 41159000000 46075000000 40499000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000 7977000000 9266000000 9177000000
+Reported Effective Tax Rate 00000 00000 00000 00000 00000 00000 00000 00000 00000
+Reported Normalized Income 6836000000
+Reported Normalized Operating Profit 7977000000
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010
+Basic EPS from Continuing Operations 00114 00031 00028 00028 00027 00022 00017 00010 00010 00015 00010
+Basic EPS from Discontinued Operations
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Continuing Operations 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Reported Normalized Diluted EPS 00010
+Basic EPS 00114 00031 00028 00028 00027 00023 00017 00010 00010 00015 00010 00001
+Diluted EPS 00112 00031 00028 00027 00026 00022 00016 00010 00010 00015 00010
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000 686465000 688804000 692741000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000 692267000 695193000 698199000
+Fiscal year end September 28th., 2022. | USD
+
+...
+
+[Message clipped]  View entire message
+
+￼
+
+ZACHRY WOOD <zachryiixixiiwood@gmail.com>
+
+Fri, Nov 11, 10:40 PM (8 hours ago)
+
+￼
+
+￼
+
+to Carolyn
+
+￼
+
+C&E 1049 Department of the Treasury --- Internal Revenue Service (99) OMB No.  1545-0074 IRS Use Only --- Do not write or staple in this space
+1040 U.S. Individual Income Tax Return       
+(IRS USE ONLY)                       575A        03-18-2022        WOOD        B        99999999999      SS-4               Earnings Statement                                                       
+
+
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see the seperate Instructions.
+Date.                                                               2022-09-01
+IRS RECIEVED  
+_________________________________________________________________________________________________________________________________________________________________________________________________________________                                  
+ (IRS USE ONLY)                       575A        03-18-2022        WOOD        B        99999999999      SS-4               Earnings Statement 
+
+Gross Pay 75698871600                                Import ant/Notes.md
+                                                                  
+_____________________________________________________     
+COMPANY PH Y: 650-253-0000
+___________________________
+Statutory BASIS OF PAY: 
+Federal Income Tax                
+Social Security Tax                
+YOUR BASIC/DILUTED EPS RATE HAS BEEN CHANGED FROM 0.001 TO 112.20 PAR SHARE VALUE
+Medicare Tax                
+       
+Net Pay 70842743866 70842743866
+CHECKING        
+Net Check 70842743866        
+Your federal taxable wages this period are $
+ALPHABET INCOME CHECK NO.
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 222129
+DEPOSIT TICKET
+Deposited to the account Of xxxxxxxx6547
+Deposits and Other Additions                                                                                           Checks and Other Deductions Amount
+Description Description I Items 5.41
+ACH Additions Debit Card Purchases 1 15.19
+POS Purchases 2 2,269,894.11
+ACH Deductions 5 82
+Service Charges and Fees 3 5.2
+Other Deductions 1 2,270,001.91
+Total Total 12
+
+
+Daily Balance
+
+Date Ledger balance Date Ledger balance Date Ledger balance
+7/30 107.8 8/3 2,267,621.92- 8/8 41.2
+8/1 78.08 8/4 42.08 8/10 2150.19-
+
+
+
+
+
+Daily Balance continued on next page
+Date
+8/3 2,267,700.00 ACH Web Usataxpymt IRS 240461564036618 (0.00022214903782823)
+8/8 Corporate ACH Acctverify Roll By ADP (00022217906234115)
+8/10 ACH Web Businessform Deluxeforbusiness 5072270 (00022222905832355)
+8/11 Corporate Ach Veryifyqbw Intuit (00022222909296656)
+8/12 Corporate Ach Veryifyqbw Intuit (00022223912710109)
+
+
+Service Charges and Fees
+Reference
+Date posted number
+8/1 10 Service Charge Period Ending 07/29.2022
+8/4 36 Returned ItemFee (nsf) (00022214903782823)
+8/11 36 Returned ItemFee (nsf) (00022222905832355)
+
+
+
+
+
+
+
+INCOME STATEMENT
+
+INASDAQ:GOOG TTM Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020 Q3 2020 Q2 2020
+
+Gross Profit 1.46698E+11 42337000000 37497000000 35653000000 31211000000 30818000000 25056000000 19744000000
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Other Revenue
+Cost of Revenue -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Cost of Goods and Services -1.10939E+11 -32988000000 -27621000000 -26227000000 -24103000000 -26080000000 -21117000000 -18553000000
+Operating Income/Expenses -67984000000 -20452000000 -16466000000 -16292000000 -14774000000 -15167000000 -13843000000 -13361000000
+Selling, General and Administrative Expenses -36422000000 -11744000000 -8772000000 -8617000000 -7289000000 -8145000000 -6987000000 -6486000000
+General and Administrative Expenses -13510000000 -4140000000 -3256000000 -3341000000 -2773000000 -2831000000 -2756000000 -2585000000
+Selling and Marketing Expenses -22912000000 -7604000000 -5516000000 -5276000000 -4516000000 -5314000000 -4231000000 -3901000000
+Research and Development Expenses -31562000000 -8708000000 -7694000000 -7675000000 -7485000000 -7022000000 -6856000000 -6875000000
+Total Operating Profit/Loss 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Non-Operating Income/Expenses, Total 12020000000 2517000000 2033000000 2624000000 4846000000 3038000000 2146000000 1894000000
+Total Net Finance Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+Net Interest Income/Expense 1153000000 261000000 310000000 313000000 269000000 333000000 412000000 420000000
+
+Interest Expense Net of Capitalized Interest -346000000 -117000000 -77000000 -76000000 -76000000 -53000000 -48000000 -13000000
+Interest Income 1499000000 378000000 387000000 389000000 345000000 386000000 460000000 433000000
+Net Investment Income 12364000000 2364000000 2207000000 2924000000 4869000000 3530000000 1957000000 1696000000
+Gain/Loss on Investments and Other Financial Instruments 12270000000 2478000000 2158000000 2883000000 4751000000 3262000000 2015000000 1842000000
+Income from Associates, Joint Ventures and Other Participating Interests 334000000 49000000 188000000 92000000 5000000 355000000 26000000 -54000000
+Gain/Loss on Foreign Exchange -240000000 -163000000 -139000000 -51000000 113000000 -87000000 -84000000 -92000000
+Irregular Income/Expenses 0 0 0 0 0
+Other Irregular Income/Expenses 0 0 0 0 0
+Other Income/Expense, Non-Operating -1497000000 -108000000 -484000000 -613000000 -292000000 -825000000 -223000000 -222000000
+Pretax Income 90734000000 24402000000 23064000000 21985000000 21283000000 18689000000 13359000000 8277000000
+Provision for Income Tax -14701000000 -3760000000 -4128000000 -3460000000 -3353000000 -3462000000 -2112000000 -1318000000
+Net Income from Continuing Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Extraordinary Items and Discontinued Operations 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income after Non-Controlling/Minority Interests 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Diluted Net Income Available to Common Stockholders 76033000000 20642000000 18936000000 18525000000 17930000000 15227000000 11247000000 6959000000
+Income Statement Supplemental Section
+Reported Normalized and Operating Income/Expense Supplemental Section
+Total Revenue as Reported, Supplemental 2.57637E+11 75325000000 65118000000 61880000000 55314000000 56898000000 46173000000 38297000000
+Total Operating Profit/Loss as Reported, Supplemental 78714000000 21885000000 21031000000 19361000000 16437000000 15651000000 11213000000 6383000000
+Reported Effective Tax Rate 0.162 0.179 0.157 0.158 0.158 0.159
+Reported Normalized Income
+Reported Normalized Operating Profit
+Other Adjustments to Net Income Available to Common Stockholders
+Discontinued Operations
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Basic EPS from Continuing Operations 113.88 31.12 28.44 27.69 26.63 22.46 16.55 10.21
+Basic EPS from Discontinued Operations
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Diluted EPS from Continuing Operations 112.2 30.67 27.99 27.26 26.29 22.23 16.4 10.13
+Diluted EPS from Discontinued Operations
+Basic Weighted Average Shares Outstanding 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted Weighted Average Shares Outstanding 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Reported Normalized Diluted EPS
+Basic EPS 113.88 31.15 28.44 27.69 26.63 22.54 16.55 10.21
+Diluted EPS 112.2 30.69 27.99 27.26 26.29 22.3 16.4 10.13
+Basic WASO 667650000 662664000 665758000 668958000 673220000 675581000 679449000 681768000
+Diluted WASO 677674000 672493000 676519000 679612000 682071000 682969000 685851000 687024000
+Fiscal year end September 28th., 2022. | USD
+Your federal taxable wages this period are $
+ALPHABET INCOME Advice number:
+1600 AMPIHTHEATRE  PARKWAY MOUNTAIN VIEW CA 94043 2.21169E+13
+
+
+
+
+GOOGL_income-statement_Quarterly_As_Originally_Reported Q4 2021 Q3 2021 Q2 2021 Q1 2021 Q4 2020
+Cash Flow from Operating Activities, Indirect 24934000000 25539000000 37497000000 31211000000 30818000000
+Net Cash Flow from Continuing Operating Activities, Indirect 24934000000 25539000000 21890000000 19289000000 22677000000
+Cash Generated from Operating Activities 24934000000 25539000000 21890000000 19289000000 22677000000
+Income/Loss before Non-Cash Adjustment 20642000000 18936000000 18525000000 17930000000 15227000000
+Total Adjustments for Non-Cash Items 6517000000 3797000000 4236000000 2592000000 5748000000
+Depreciation, Amortization and Depletion, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation and Amortization, Non-Cash Adjustment 3439000000 3304000000 2945000000 2753000000 3725000000
+Depreciation, Non-Cash Adjustment 3215000000 3085000000 2730000000 2525000000 3539000000
+Amortization, Non-Cash Adjustment 224000000 219000000 215000000 228000000 186000000
+Stock-Based Compensation, Non-Cash Adjustment 3954000000 3874000000 3803000000 3745000000 3223000000
+Taxes, Non-Cash Adjustment 1616000000 -1287000000 379000000 1100000000 1670000000
+Investment Income/Loss, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Gain/Loss on Financial Instruments, Non-Cash Adjustment -2478000000 -2158000000 -2883000000 -4751000000 -3262000000
+Other Non-Cash Items -14000000 64000000 -8000000 -255000000 392000000
+Changes in Operating Capital -2225000000 2806000000 -871000000 -1233000000 1702000000
+Change in Trade and Other Receivables -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Trade/Accounts Receivable -5819000000 -2409000000 -3661000000 2794000000 -5445000000
+Change in Other Current Assets -399000000 -1255000000 -199000000 7000000 -738000000
+Change in Payables and Accrued Expenses 6994000000 3157000000 4074000000 -4956000000 6938000000
+Change in Trade and Other Payables 1157000000 238000000 -130000000 -982000000 963000000
+Change in Trade/Accounts Payable 1157000000 238000000 -130000000 -982000000 963000000
+Change in Accrued Expenses 5837000000 2919000000 4204000000 -3974000000 5975000000
+Change in Deferred Assets/Liabilities 368000000 272000000 -3000000 137000000 207000000
+Change in Other Operating Capital -3369000000 3041000000 -1082000000 785000000 740000000
+Change in Prepayments and Deposits
+Cash Flow from Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Cash Flow from Continuing Investing Activities -11016000000 -10050000000 -9074000000 -5383000000 -7281000000
+Purchase/Sale and Disposal of Property, Plant and Equipment, Net -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Purchase of Property, Plant and Equipment -6383000000 -6819000000 -5496000000 -5942000000 -5479000000
+Sale and Disposal of Property, Plant and Equipment
+Purchase/Sale of Business, Net -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Acquisition of Business -385000000 -259000000 -308000000 -1666000000 -370000000
+Purchase/Sale of Investments, Net -4348000000 -3360000000 -3293000000 2195000000 -1375000000
+Purchase of Investments -40860000000 -35153000000 -24949000000 -37072000000 -36955000000
+Sale of Investments 36512000000 31793000000 21656000000 39267000000 35580000000
+Other Investing Cash Flow 100000000 388000000 23000000 30000000 -57000000
+Purchase/Sale of Other Non-Current Assets, Net
+Sales of Other Non-Current Assets
+Cash Flow from Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Cash Flow from Continuing Financing Activities -16511000000 -15254000000 -15991000000 -13606000000 -9270000000
+Issuance of/Payments for Common Stock, Net -13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Payments for Common Stock 13473000000 -12610000000 -12796000000 -11395000000 -7904000000
+Proceeds from Issuance of Common Stock
+Issuance of/Repayments for Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Issuance of/Repayments for Long Term Debt, Net 115000000 -42000000 -1042000000 -37000000 -57000000
+Proceeds from Issuance of Long Term Debt 6250000000 6350000000 6699000000 900000000 0
+Repayments for Long Term Debt 6365000000 -6392000000 -7741000000 -937000000 -57000000
+Proceeds from Issuance/Exercising of Stock Options/Warrants 2923000000 -2602000000 -2453000000 -2184000000 -1647000000
+
+
+Other Financing Cash Flow 0
+Cash and Cash Equivalents, End of Period 20945000000 23719000000 300000000 10000000 338000000000)
+Change in Cash 25930000000 235000000000) 23630000000 26622000000 26465000000
+Effect of Exchange Rate Changes 181000000000) -146000000000) -3175000000 300000000 6126000000
+Cash and Cash Equivalents, Beginning of Period 2.3719E+13 2.363E+13 183000000 -143000000 210000000
+Cash Flow Supplemental Section 2774000000) 89000000 266220000000000) 26465000000000) 20129000000000)
+Change in Cash as Reported, Supplemental 13412000000 157000000 -2992000000 6336000000
+Income Tax Paid, Supplemental 2774000000 89000000 2.2677E+15 -4990000000
+Cash and Cash Equivalents, Beginning of Period
+
+12 Months Ended
+_________________________________________________________
+Q4 2020 Q4  2019
+Income Statement
+USD in "000'"s
+Repayments for Long Term Debt Dec. 31, 2020 Dec. 31, 2019
+Costs and expenses:
+Cost of revenues 182527 161857
+Research and development
+Sales and marketing 84732 71896
+General and administrative 27573 26018
+European Commission fines 17946 18464
+Total costs and expenses 11052 9551
+Income from operations 0 1697
+Other income (expense), net 141303 127626
+Income before income taxes 41224 34231
+Provision for income taxes 6858000000 5394
+Net income 22677000000 19289000000
+*include interest paid, capital obligation, and underweighting 22677000000 19289000000
+22677000000 19289000000
+Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)
+Diluted net income per share of Class A and Class B common stock and Class C capital stock (in dollars par share)
+
+
+For Disclosure, Privacy Act, and Paperwork Reduction Act Notice, see the seperate Instructions.
+
+
+
+
+Date.                       
+2022-09-01
+IRS RECIEVED  
+Report Range 5/4/2022 - 6/4/2022 88-1656496  Loca ID:      28 :l ID: 633441725 State: All Local ID: txdl 00037305581 SRVCCHG /*  */$2,267,700.00                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                                                                                                        
++Taxes / Deductions                Stub Number: 1                                                                 -                                                                                                                                                                                                                                                                                                                                                                                                                                                                
++Taxable Maritial Status: Single        -                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
++TX: 28                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                                                                                                        
++EIN:                Total Year to Date                                                                                                                                                                                                                                                                                                                                                                                                                                                        
++Internal Revenue Service        Due 04/18/2022                2022 Form 1040-ES Payment Voucher 1                                        Pay Day        1/30/2022                                                                                                                                         ++        MOUNTAIN VIEW, C.A., 94043                                                                                                                                                                                                                 ++        Taxable Marital Status :                                                                                                                                                                                                                 ++        Exemptions/Allowances :                                                                                                                                                                                                                 ++        Federal :                                                                                                                                                                                                                 ++        TX : 28        rate        units        this period        year to date        Other Benefits and                         ZACHRY T                                                                                                                                                 ++        Current assets:                                0        Information                        WOOD                                                                                                                                                 ++        Cash and cash equivalents        26465        18498                0        Total Work Hrs                                                                                                                                                                         ++        Marketable securities        110229        101177                0        Important Notes                        DALLAS                                                                                                                                                 ++        Total cash, cash equivalents, and marketable securities        136694        119675                0        COMPANY PH/Y: 650-253-0000                                                0                                                                                                                         ++        Accounts receivable, net        30930        25326                0        BASIS OF PAY : BASIC/DILUTED EPS                                                                                                                                                                         ++        Income taxes receivable, net        454        2166                0                                                                                                                                                                                 ++        Inventory        728        999                0                                Pto Balance                                                                                                                                                 ++        Other current assets        5490        4412                0                                                                                                                                                                                 ++        Total current assets        174296        152578                0                                                                                                                                                                                 ++        Non-marketable investments        20703        13078                0        70842743866                                                                                                                                                                         ++        Deferred income taxes        1084        721                0                                                                                                                                                                                 ++        Property and equipment, net        84749        73646                0        $70,842,743,866.00                                                                                                                                                                          ++        Operating lease assets        12211        10941                0                                                                                                                                                                                 ++        Intangible assets, net        1445        1979                0                                                                                                                                                                                 ++        Goodwill        21175        20624                0                        Advice date :        650001                                                                                                                                                 ++        Other non-current assets        3953        2342                0                        Pay date :        4/18/2022                                                                                                                                                 ++        PLEASE READ THE IMPORTANT DISCLOSURES BELOW.        319616        275909                0                        :xxxxxxxxx6547        JAn 29th., 2022                                                                                                                                                 ++        Paid to the account Of :                                0                                519                                                                                                                                                 ++        Accounts payable        5589        5561                0                                NON-NEGOTIABLE                                                                                                                                                 ++        Accrued compensation and benefits        11086        8495                0                                                                                                                                                                                 ++        Accrued expenses and other current liabilities        28631        23067                0                                                                                                                                                                                 ++        Accrued revenue share        7500        5916                0                                                                                                                                                                                 ++        Deferred revenue        2543        1908                0                                                                                                                                                                                 ++        Income taxes payable, net        1485        274                0                                                                                                                                                                                 ++        Total current liabilities        56834        45221                0                                                                                                                                                                                 ++        Long-term debt        13932        4554                0                                                                                                                                                                                 ++        Deferred revenue, non-current        481        358                0                                                                                                                                                                                 ++        Income taxes payable, non-current        8849        9885                0                                                                                                                                                                                 ++        Deferred income taxes        3561        1701                0                                                                                                                                                                                 ++                11146        10214                0                                                                                                                                                                                 ++        Other long-term liabilities        2269        2534                0                                                                                                                                                                                 ++        Total liabilities        97072        74467                0                                                                                                                                                                                 ++        Commitments and Contingencies (Note 10)                                  0                                                                                                                                                                                 ++        Stockholders’ equity:                                0                                                                                                                                                                                 ++        Convertible preferred stock, $0.001 par value per share, 100,000 shares authorized; no shares issued and outstanding        0        0                0                                                                                                                                                                                 ++        Class A and Class B common stock, and Class C capital stock and additional paid-in capital, $0.001 par value per share: 15,000,000 shares authorized (Class A 9,000,000, Class B 3,000,000, Class C 3,000,000); 688,335 (Class A 299,828, Class B 46,441, Class C 342,066) and 675,222 (Class A 300,730, Class B 45,843, Class C 328,649) shares issued and outstanding        58510        50552                0                                                                                                                                                                                 ++        Accumulated other comprehensive income (loss)        633        -1232                0                                                                                                                                                                                 ++        Retained earnings        163401        152122                0                                                                                                                                                                                 ++        Total stockholders’ equity        222544        201442                0                                                                                                                                                                                 ++        Total liabilities and stockholders’ equity        319616        275909                0                                                                                                                                                                                 ++        Convertible preferred stock, par value (in dollars per share)        0.001        0.001                0                                                                                                                                                                                 ++        Convertible preferred stock, shares authorized (in shares)        100000000        100000000                0                                                                                                                                                                                 ++        Convertible preferred stock, shares issued (in shares)        0        0                0                                                                                                                                                                                 ++        Convertible preferred stock, shares outstanding (in shares)        0        0                0                                                                                                                                                                                 ++        Schedule II: Valuation and Qualifying Accounts (Details) - Allowance for doubtful accounts and sales credits - USD ($) $ in Millions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019        Dec. 31, 2018        0                                                                                                                                                                                 ++        SEC Schedule, 12-09, Movement in Valuation Allowances and Reserves [Roll Forward]                                0                                                                                                                                                                                 ++        Revenues (Narrative) (Details) - USD ($) $ in Billions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenue from Contract with Customer [Abstract]                                0                                                                                                                                                                                 ++        Deferred revenue                2.3                0                                                                                                                                                                                 ++        Revenues recognized        1.8                        0                                                                                                                                                                                 ++        Transaction price allocated to remaining performance obligations        29.8                        0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2021-01-01                                0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                 ++        Expected timing of revenue recognition        24 months                        0                                                                                                                                                                                 ++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2023-01-01                                0                                                                                                                                                                                 ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                 ++        Expected timing of revenue recognition                                 0                                                                                                                                                                                 ++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 ++        Information about Segments and Geographic Areas (Long-Lived Assets by Geographic Area) (Details) - USD ($) $ in Millions        Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        96960        84587                0                                                                                                                                                                                 ++        United States                                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        69315        63102                0                                                                                                                                                                                 ++        International                                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        27645        21485                0                                                                                                                                                                                 ++                2016        2017        2018        2019        2020        2021        TTM                                                                                                                                                         ++                2.23418E+11        2.42061E+11        2.25382E+11        3.27223E+11        2.86256E+11        3.54636E+11        3.54636E+11                                                                                                                                                         ++                45881000000        60597000000        57418000000        61078000000        63401000000        69478000000        69478000000                                                                                                                                                         ++                3143000000        3770000000        4415000000        4743000000        5474000000        6052000000        6052000000                                                                                                                                                         ++         Net Investment Income, Revenue        9531000000        13081000000        10565000000        17214000000        14484000000        8664000000        -14777000000        81847000000        48838000000        86007000000        86007000000                                                                                                                         ++         Realized Gain/Loss on Investments, Revenue        472000000        184000000        72000000        10000000        7553000000        1410000000        -22155000000        71123000000        40905000000        77576000000        77576000000                                                                                                                         ++         Gains/Loss on Derivatives, Revenue        1963000000        2608000000        506000000        974000000        751000000        718000000        -300000000        1484000000        -159000000        966000000        966000000                                                                                                                         ++         Interest Income, Revenue        6106000000        6408000000        6484000000        6867000000        6180000000        6536000000        7678000000        9240000000        8092000000        7465000000        7465000000                                                                                                                         ++         Other Investment Income, Revenue        990000000        3881000000        3503000000        9363000000                                                                                                                                                                                 ++         Rental Income, Revenue                                        2553000000        2452000000        5732000000        5856000000        5209000000        5988000000        5988000000                                                                                                                         ++         Other Revenue        1.18387E+11        1.32385E+11        1.42881E+11        1.52435E+11        1.57357E+11        1.66578E+11        1.72594E+11        1.73699E+11        1.63334E+11        1.87111E+11        1.87111E+11                                                                                                                         ++        Total Expenses        -1.40227E+11        -1.53354E+11        -1.66594E+11        -1.75997E+11        -1.89751E+11        -2.18223E+11        -2.21381E+11        -2.24527E+11        -2.30563E+11        -2.4295E+11        -2.4295E+11                                                                                                                         ++         Benefits,Claims and Loss Adjustment Expense, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                         ++         Policyholder Future Benefits and Claims, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                         ++         Other Underwriting Expenses        -7693000000        -7248000000        -6998000000        -7517000000        -7713000000        -9321000000        -9793000000        -11200000000        -12798000000        -12569000000        -12569000000                                                                                                                         ++         Selling, General and Administrative Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -19308000000        -20644000000        -21917000000        -23229000000        -23329000000        -23044000000        -23044000000                                                                                                                         ++         Rent Expense                                        -1335000000        -1455000000        -4061000000        -4003000000        -3520000000        -4201000000        -4201000000                                                                                                                         ++         Selling and Marketing Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -17973000000        -19189000000        -17856000000        -19226000000        -19809000000        -18843000000        -18843000000                                                                                                                         ++         Other Income/Expenses        -92693000000        -1.03676E+11        -1.11009E+11        -1.17594E+11        -1.24061E+11        -1.32377E+11        -1.37664E+11        -1.37775E+11        -1.30645E+11        -1.48189E+11        -1.48189E+11                                                                                                                         ++         Total Net Finance Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Net Interest Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Interest Expense Net of Capitalized Interest        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         ++         Income from Associates, Joint Ventures and Other Participating Interests                        -26000000        -122000000        1109000000        3014000000        -2167000000        1176000000        726000000        995000000        995000000                                                                                                                         ++         Irregular Income/Expenses                                                        -382000000        -96000000        -10671000000        .        .                                                                                                                         ++         Impairment/Write Off/Write Down of Capital Assets                                                        -382000000        -96000000        -10671000000        .        .                                                                                                                         ++        Pretax Income        22236000000        28796000000        28105000000        34946000000        33667000000        23838000000        4001000000        1.02696E+11        55693000000        1.11686E+11        1.11686E+11                                                                                                                         ++        Provision for Income Tax        -6924000000        -8951000000        -7935000000        -10532000000        -9240000000        21515000000        321000000        -20904000000        -12440000000        -20879000000        -20879000000                                                                                                                         ++        Net Income from Continuing Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                         ++        Net Income after Extraordinary Items and Discontinued Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                         ++        Non-Controlling/Minority Interests        -488000000        -369000000        -298000000        -331000000        -353000000        -413000000        -301000000        -375000000        -732000000        -1012000000        -1012000000                                                                                                                         ++        Net Income after Non-Controlling/Minority Interests        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Diluted Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                         ++        Income Statement Supplemental Section                                                                                                                                                                                                                 ++         Reported Normalized and Operating Income/Expense Supplemental Section                                                                                                                                                                    
+Fri, Dec 9 at 10:05 PM                                                                                                Business Checking For 24-hour account information, sign on to                                                                                
+                                                                                                pnc.com/mybusiness/ Business Checking Account number: 47-2041-6547 - continued                                                                                
+                                                                                                Activity Detail                                                                                
+                ZACHRY T WOOD                                                                                                                                                        Deposits and Other Additions                                                                                
+                Cash and Cash Equivalents, Beginning of Period                                                                                                                                                        ACH Additions                                                                                
+                Department of the Treasury                                                                                                                                                        Date posted                                                                                
+                Internal Revenue Service                                                                                                                                                        27-Apr        
+                                                                                                Checks and Other Deductions                                                                                
+                Calendar Year                                                                                                                                                Operating Income/Expenses                                                             -67984000000        -20452000000        -16466000000        -16292000000        -14774000000        -15167000000        -13843000000        -13361000000        Deductions                                                                                
+                Due: 04/18/2022                                                                                                                                                Selling, General and Administrative Expenses                                                -36422000000        -11744000000        -8772000000        -8617000000        -7289000000        -8145000000        -6987000000        -6486000000        Date posted                                                                                
+                                                                                        General and Administrative                                                                          13510000000        -4140000000        -3256000000        -3341000000        -2773000000        -2831000000        -2756000000        -2585000000        26-Apr        
+                USD in "000'"s                                                                                                                                                Selling and Marketing Expenses                                                                   -22912000000        -7604000000        -5516000000        -5276000000        -4516000000        -5314000000        -4231000000        -3901000000        Service Charges and Fees                                                                                
+                Repayments for Long Term Debt                                                                                                                                                Research and Development Expenses                                                           -31562000000        -8708000000        -7694000000        -7675000000        -7485000000        -7022000000        -6856000000        -6875000000        Date posted                                                                                
+                Costs and expenses:                                                                                                                                                Total Operating Profit/Loss                                                                        78714000000        21885000000        21031000000        19361000000        16437000000        15651000000        11213000000        6383000000        27-Apr        
+                Cost of revenues                                                                                                                                                Non-Operating Income/Expenses, Total                                                                 12020000000        2517000000        2033000000        2624000000        4846000000        3038000000        2146000000        1894000000        Detail of Services Used During Current Period                                                                                
+                Research and development                                                                                                                                                Total Net Finance Income/Expense                                                                                  1153000000        261000000        310000000        313000000        269000000        333000000        412000000        420000000        Note: The total charge for the following services will be posted to your account on 05/02/2022 and will appear on your next statement a Charge Period Ending 04/29/2022,                                                                                
+                Sales and marketing                                                                                                                                                Net Interest Income/Expense                                                                                            1153000000      261000000        310000000        313000000        269000000        333000000        412000000        420000000        Description                                                                                
+                General and administrative                                                                                                                                                Income from Associates, Joint Ventures and Other Participating Interests                                          334000000        49000000        188000000        92000000        5000000        355000000        26000000        -54000000        Account Maintenance Charge                                                                                
+                European Commission fines                                                                                                                                                Interest Expense Net of Capitalized Interest                                                                          -346000000        -117000000        -77000000        -76000000        -76000000        -53000000        -48000000        -13000000        Total For Services Used This Peiiod                                                                                
+                Total costs and expenses                                                                                                                                                Pretax Income                                                                                        90734000000        24402000000        23064000000        21985000000        21283000000        18689000000        13359000000        8277000000        Total Service (harge                                                                                
+                Income from operations                                                                                                                                                Provision for Income Tax                                                                         14701000000-       3760000000-         4128000000-        3460000000-         3353000000-        3462000000-         2112000000-        1318000000-        Reviewing Your Statement                                                                                
+                Other income (expense), net                                                                                                                                                Net Investment Income                                                                                     12364000000        2364000000        2207000000        2924000000        4869000000        3530000000        1957000000        1696000000        Please review this statement carefully and reconcile it with your records. Call the telephone number on the upper right side of the first page of this statement if: you have any questions regarding your account(s); your name or address is incorrect; • you have any questions regarding interest paid to an interest-bearing account.                                                                                
+                Income before income taxes                                                                                                                                                Interest Income                                                                                            1499000000        378000000        387000000        389000000        345000000        386000000        460000000        433000000                       Balancing Your Account Update Your Account Register           
+                Provision for income taxes                                                                                                                                                Total Revenue as Reported, Supplemental                                                   2.57637E+11        75325000000        65118000000        61880000000        55314000000        56898000000        46173000000        38297000000                
+                Net income                                                                                                                                                                           2.57637E+11        75325000000        65118000000        61880000000        55314000000        56898000000        46173000000        38297000000                
+                                                                                        Gross Profit                                                                                         1.46698E+11        42337000000        37497000000        35653000000        31211000000        30818000000        25056000000        19744000000                
+                 **Does not include interest paid, capital obligation, and underweighting                                                                                                                                                Irregular Income/Expenses                                                                                                                                                                                                  0        0                                0        0        0                 
+                                                                                        Other Irregular Income/Expenses                                                                                                                                                                                           0        0                                0        0        0                
+                Basic net income per share of Class A and B common stock and Class C capital stock (in dollars par share)                                                                                                                                                Other Revenue                                                                                
+                                                                                        Cost of Revenue                                                                           -1.10939E+11        -32988000000        -27621000000        -26227000000        -24103000000        -26080000000        -21117000000        -18553000000                
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                        
+                                                                                                                                                                                                                                                                                                                                                                                                                                                              
+                +Internal Revenue Service        Due 04/18/2022                2022 Form 1040-ES Payment Voucher 1
+                                        Pay Day        1/30/2022                                                                                                                                         \
+Employee Information :      Local ID:      38     
+
+                 Taxable Marital Status :                                                                                                                                                                                                               
+  ++        Exemptions/Allowances :                                                                                                                                                                                 
+                               
+
+ ++        Federal :                                                                                                                                                                                                                 
+++        TX : 28        rate        units        this period        year to date        Other Benefits and                        
+ ZACHRY T                                                                                                                                                 +
++        Current assets:                                0        Information                      
+  WOOD                                                                                                                                               
+  ++        Cash and cash equivalents        26465        18498                0        Total Work Hrs                                                                                                                                                                       
+  ++        Marketable securities        110229        101177                0        Important Notes                        DALLAS                                                                                                                                              
+
+   ++        Total cash, cash equivalents, and marketable securities        136694        119675                0        COMPANY PH/Y: 650-253-0000                                                0                                                                                                                       
+  ++        Accounts receivable, net        30930        25326                0        BASIS OF PAY : BASIC/DILUTED EPS                                                                                                                                                                         +
++        Income taxes receivable, net        454        2166                0                                                                                                                                                                               
+  ++        Inventory        728        999                0                                Pto Balance                                                                                                                                                 
+++        Other current assets        5490        4412                0                                                                                                                                                                                 
+++        Total current assets        174296        152578                0                                                                                                                                                                             
+    ++        Non-marketable investments        20703        13078                0        70842743866                                                                                                                                                                      
+   ++        Deferred income taxes        1084        721                0                                                                                                                                                                                
+ ++        Property and equipment, net        84749        73646                0        $70,842,743,866.00                                                                                                                                   
+                                       ++        Operating lease assets        12211        10941                0                                                                                                                                                                                
+ ++        Intangible assets, net        1445        1979                0                                                                                                                                                                                
+ ++        Goodwill        21175        20624                0                        Advice date :        650001                                                                                                                                               
+  ++        Other non-current assets        3953        2342                0                        Pay date :        4/18/2022                                                                                                                                               
+  ++        PLEASE READ THE IMPORTANT DISCLOSURES BELOW.        319616        275909                0                        :xxxxxxxxx6547        JAn 29th., 2022                                                                                                                                                 +
++        Paid to the account Of :                                0                                519                                                                                                                                                
+ ++        Accounts payable        5589        5561                0                                NON-NEGOTIABLE                                                                                                                                              
+   ++        Accrued compensation and benefits        11086        8495                0                                                                                                                                                                               
+  ++        Accrued expenses and other current liabilities        28631        23067                0                                                                                                                                                                                
+ ++        Accrued revenue share        7500        5916                0                                                                                                                                                                                
+ ++        Deferred revenue        2543        1908                0                                                                                                                                                                                 
+++        Income taxes payable, net        1485        274                0                                                                                                                                                                               
+  ++        Total current liabilities        56834        45221                0                                                                                                                                                                                
+ ++        Long-term debt        13932        4554                0
+++        Deferred revenue, non-current        481        358                0                                                                                                                                                                                 +
++        Income taxes payable, non-current        8849        9885                0                                                                                                                                                                                 
+++        Deferred income taxes        3561        1701                0                                                                                                                                                                                
+ ++                11146        10214                0                                                                                                                                                                                 
+++        Other long-term liabilities        2269        2534                0                                                                                                                                                                 
+              
+  ++        Total liabilities        97072        74467                0                                                                                                                                                                                 +
++        Commitments and Contingencies (Note 10)                                  0                                                                                                                                                                             
+    ++        Stockholders’ equity:                                0                                                                                                                                                                              
+   ++        Convertible preferred stock, $0.001 par value per share, 100,000 shares authorized; no shares issued and outstanding        0        0                0                                                                                                                                                                                
+ ++        Class A and Class B common stock, and Class C capital stock and additional paid-in capital, $0.001 par value per share: 15,000,000 shares authorized (Class A 9,000,000, Class B 3,000,000, Class C 3,000,000); 688,335 (Class A 299,828, Class B 46,441, Class C 342,066) and 675,222 (Class A 300,730, Class B 45,843, Class C 328,649) shares issued and outstanding        58510        50552                0                                                                                                                                                                                 ++        Accumulated other comprehensive income (loss)        633        -1232                0                                                                                                                                                                                 ++        Retained earnings        163401        152122                0                                                                                                                                                                              
+  ++        Total stockholders’ equity        222544        201442                0                                                                                                                                                                                 +
++        Total liabilities and stockholders’ equity        319616        275909                0                                                                                                                                                                                 
+++        Convertible preferred stock, par value (in dollars per share)        0.001        0.001                0                                                                                                                                                                                 ++        Convertible preferred stock, shares authorized (in shares)        100000000        100000000                0                                                                                                                                                                                 ++        Convertible preferred stock, shares issued (in shares)        0        0                0                                                                                                                                                                                 
+++        Convertible preferred stock, shares outstanding (in shares)        0        0                0                                                                                                                                                                                 ++        Schedule II: Valuation and Qualifying Accounts (Details) - Allowance for doubtful accounts and sales credits - USD ($) $ in Millions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019        Dec. 31, 2018        0                                                                                                                                                                                 ++        SEC Schedule, 12-09, Movement in Valuation Allowances and Reserves [Roll Forward]                                0                                                                                                                                                                                 ++        Revenues (Narrative) (Details) - USD ($) $ in Billions        12 Months Ended                        0                                                                                                                                                                                 ++                Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenue from Contract with Customer [Abstract]                                0                                                                                                                                                                                 
+++        Deferred revenue                2.3                0                                                                                                                                                                                 
+++        Revenues recognized        1.8                        0                                                                                                                                                                                 
+++        Transaction price allocated to remaining performance obligations        29.8                        0                                                                                                                                                                                
+ ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2021-01-01                                0                                                                                                                                                                               
+  ++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                 \
+++        Expected timing of revenue recognition        24 months                        0                                                                                                                                                                                 
+++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 
+++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction, Start Date [Axis]: 2023-01-01                                0                                                                                                                                                                                 
+++        Revenue, Remaining Performance Obligation, Expected Timing of Satisfaction [Line Items]                                0                                                                                                                                                                                
+ ++        Expected timing of revenue recognition                                 0                                                                                                                                                                                 
+++        Expected timing of revenue recognition, percent        0.5                        0                                                                                                                                                                                 
+++        Information about Segments and Geographic Areas (Long-Lived Assets by Geographic Area) (Details) - USD ($) $ in Millions        Dec. 31, 2020        Dec. 31, 2019                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        96960        84587                0                                                                                                                                                                                 ++        United States                                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        69315        63102                0                                                                                                                                                                                 ++        International                                0                                                                                                                                                                                 ++        Revenues from External Customers and Long-Lived Assets [Line Items]                                0                                                                                                                                                                                 ++        Long-lived assets        27645        21485                0                                                                                                                                                                                 ++                2016        2017        2018        2019        2020        2021        TTM                                                                                                                                                         
+++                2.23418E+11        2.42061E+11        2.25382E+11        3.27223E+11        2.86256E+11        3.54636E+11        3.54636E+11                                                                                                                                                         ++                45881000000        60597000000        57418000000        61078000000        63401000000        69478000000        69478000000                                                                                                                                                         ++                3143000000        3770000000        4415000000        4743000000        5474000000        6052000000        6052000000                                                                                                                                                         ++         Net Investment Income, Revenue        9531000000        13081000000        10565000000        17214000000        14484000000        8664000000        -14777000000        81847000000        48838000000        86007000000        86007000000                                                                                                                         ++         Realized Gain/Loss on Investments, Revenue        472000000        184000000        72000000        10000000        7553000000        1410000000        -22155000000        71123000000        40905000000        77576000000        77576000000                                                                                                                         ++         Gains/Loss on Derivatives, Revenue        1963000000        2608000000        506000000        974000000        751000000        718000000        -300000000        1484000000        -159000000        966000000        966000000                                                                                                                         
+++         Interest Income, Revenue        6106000000        6408000000        6484000000        6867000000        6180000000        6536000000        7678000000        9240000000        8092000000        7465000000        7465000000                                                                                                                       
+  ++         Other Investment Income, Revenue        990000000        3881000000        3503000000        9363000000                                                                                                                                                                                 
+++         Rental Income, Revenue                                        2553000000        2452000000        5732000000        5856000000        5209000000        5988000000        5988000000                                                                                                                         
+++         Other Revenue        1.18387E+11        1.32385E+11        1.42881E+11        1.52435E+11        1.57357E+11        1.66578E+11        1.72594E+11        1.73699E+11        1.63334E+11        1.87111E+11        1.87111E+11                                                                                                                    
+     ++        Total Expenses        -1.40227E+11        -1.53354E+11        -1.66594E+11        -1.75997E+11        -1.89751E+11        -2.18223E+11        -2.21381E+11        -2.24527E+11        -2.30563E+11        -2.4295E+11        -2.4295E+11                                                                                                                       
+  ++         Benefits,Claims and Loss Adjustment Expense, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                
+         ++         Policyholder Future Benefits and Claims, Net        -25227000000        -26347000000        -31587000000        -31940000000        -36037000000        -54509000000        -45605000000        -49442000000        -49763000000        -55971000000        -55971000000                                                                                                                    
+    ++         Other Underwriting Expenses        -7693000000        -7248000000        -6998000000        -7517000000        -7713000000        -9321000000        -9793000000        -11200000000        -12798000000        -12569000000        -12569000000                                                                                                                       
+  ++         Selling, General and Administrative Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -19308000000        -20644000000        -21917000000        -23229000000        -23329000000        -23044000000        -23044000000                                                                                                                       
+  ++         Rent Expense                                        -1335000000        -1455000000        -4061000000        -4003000000        -3520000000        -4201000000        -4201000000                                                                                                               
+          ++         Selling and Marketing Expenses        -11870000000        -13282000000        -13721000000        -15309000000        -17973000000        -19189000000        -17856000000        -19226000000        -19809000000        -18843000000        -18843000000                                                                                                                        
+ ++         Other Income/Expenses        -92693000000        -1.03676E+11        -1.11009E+11        -1.17594E+11        -1.24061E+11        -1.32377E+11        -1.37664E+11        -1.37775E+11        -1.30645E+11        -1.48189E+11        -1.48189E+11                                                                                                                        
+ ++         Total Net Finance Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                      
+   ++         Net Interest Income/Expense        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                   
+      ++         Interest Expense Net of Capitalized Interest        -2744000000        -2801000000        -3253000000        -3515000000        -3741000000        -4386000000        -3853000000        -3961000000        -4083000000        -4172000000        -4172000000                                                                                                                         +
++         Income from Associates, Joint Ventures and Other Participating Interests                        -26000000        -122000000        1109000000        3014000000        -2167000000        1176000000        726000000        995000000        995000000                                                                                                                      
+   ++         Irregular Income/Expenses                                                        -382000000        -96000000        -10671000000        .        .                                                                                                                        
+ ++         Impairment/Write Off/Write Down of Capital Assets                                                        -382000000        -96000000        -10671000000        .        .                                                                                                                         
+++        Pretax Income        22236000000        28796000000        28105000000        34946000000        33667000000        23838000000        4001000000        1.02696E+11        55693000000        1.11686E+11        1.11686E+11                                                                                                                  
+       ++        Provision for Income Tax        -6924000000        -8951000000        -7935000000        -10532000000        -9240000000        21515000000        321000000        -20904000000        -12440000000        -20879000000        -20879000000                                                                                                                      
+   ++        Net Income from Continuing Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                         
+++        Net Income after Extraordinary Items and Discontinued Operations        15312000000        19845000000        20170000000        24414000000        24427000000        45353000000        4322000000        81792000000        43253000000        90807000000        90807000000                                                                                                                        
+ ++        Non-Controlling/Minority Interests        -488000000        -369000000        -298000000        -331000000        -353000000        -413000000        -301000000        -375000000        -732000000        -1012000000        -1012000000                                                                                                                       
+  ++        Net Income after Non-Controlling/Minority Interests        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                 
+        ++        Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                      
+   ++        Diluted Net Income Available to Common Stockholders        14824000000        19476000000        19872000000        24083000000        24074000000        44940000000        4021000000        81417000000        42521000000        89795000000        89795000000                                                                                                                       
+  ++        Income Statement Supplemental Section                                                                                                                                                                                                                 
+++         Reported Normalized and Operating Income/Expense Supplemental Section                                                                                                                                                    
+                +$$22677000000000.00                                                                                        
+                +Payment Amount (Total) $9,246,754,678,763.00 Display All                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +1. Social Security (Employee + Employer) $26,661.80                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +2. Medicare (Employee + Employer) $861,193,422,444.20 Hourly                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +3. Federal Income Tax $8,385,561,229,657.00                                                                                                                                                                                                                                                                                                                                                                                                                                                        
+                +Note: this Report is generated based on THE payroll data for your reference only. Pease contact IRS office for special cases such as late Payment, previous overpayment, penalty                                       
+                 We assigned you Employer Identification Number :        88-1303491                                                      Best Time To Call                                                                                                                                                                                                                                                                                                                                                                                                  
+                +Note: This report doesn't include the pay back amount of                                                                                                                                                                                                                                                                                                                                                                                                                                                                       
+                        this period       year to date        Taxes / Deductions   Current      YTD                                                                                                                                                                                                                                                                                                                                                                        
+                                +                        Sch.      70842745000        70842745000        Federal Withholding        0        0                                                                                                                                                                                                                                                                                                                                                                        
+                ZACHRY T WOOD                +                        GROSS     70842745000        70842745000        Federal Withholding        0        0                                                                                                                                                                                                                                                                                                                                                                        
+                ALPHABET                +                        net, pay. 70842745000        70842745000        Federal Withholding        0        0                                                                                                                                                                                                                                                                                                                                                                        
+                5323 BRADFORD DR                +                        FICA - Medicare        0     70842745000        FUTA        0        0                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     
+                DALLAS, TX 75235                                         FICA - Social Security 0        8854            FUTA        0        0                                                                                                                                                                                                                                                                                                                                                                                             
+                                70842745000                                                              SUTA - 0       0                                                                        
+                                +                        ATAA -                                          RUTA - 0       0  
+                                                                       
+                                                                                                        
