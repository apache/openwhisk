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

# OpenWhisk on Vagrant

The following instructions were tested on Mac OS X El Capitan, Ubuntu 16.04 LTS.

## Requirements
-   Install [VirtualBox](https://www.virtualbox.org/wiki/Downloads) (tested with version 6.0.2)
-   Install [Vagrant](https://www.vagrantup.com/downloads.html) (tested with version 2.2.3)

*There is an issue with the `ubuntu/xenial64` image (v20190118.0.0 at the time of writing this) on MacOS Mojave 10.14.2 and VirtualBox 6. This can be fixed by replacing this image with `bento/ubuntu-16.04`. See below the "Override Vagrant Box" section to find out how to use another image.*

## Setup

### Clone the repository and change directory to `tools/vagrant`

```
git clone --depth=1 https://github.com/apache/openwhisk.git openwhisk
cd openwhisk/tools/vagrant
```

### Create a Vagrant VM

#### Option A: Using the default local CouchDB container
**Important** We advise that you use this method for development of OpenWhisk.

```
# Configure with couchdb docker container running inside the VM
./hello
```
#### Option B - Using a remote Database

**Note:** Follow instructions [tools/db/README.md](../db/README.md) on how to
configure the remote DB for OpenWhisk.

##### Option B.1 - Setting a remote Cloudant DB
```
# Provide credentials for cloudant database with admin permissions
OW_DB=cloudant OW_DB_USERNAME=xxxxx OW_DB_PASSWORD=yyyyyy ./hello
```

##### Option B.2 - Setting a remote CouchDB

```
# Provide credentials for couchdb database with admin permissions
export OW_DB=couchdb
export OW_DB_USERNAME=<username>
export OW_DB_PASSWORD=<password>
export OW_DB_PROTOCOL=http
export OW_DB_HOST=<ip_address>
export OW_DB_PORT=5984 ./hello
```

**Note:**
Data will persist after [safe re-deploy](#safe-re-deploy-after-vm-restart),
but will be destroyed if you
initialize the DB. For more information on data store configurations see
[tools/db/README.md](../db/README.md).


### Wait for hello action output
```
wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```
**Tip:**
The very first build may take 30 minutes or more depending on network speed. If
there are any build failures, it might be due to network timeouts, to recover
follow the manual process to build and deploy in
[ansible/README.md](../../ansible/README.md)

**Tip:**
By default, each `docker` command will timeout after 840 seconds (14 minutes).
If you're on a really slow connection, this might be too short. You can modify
the timeout value in [docker.gradle](../../gradle/docker.gradle#L22).


### Using CLI from outside the VM
You can use the CLI from the host machine as well as from inside the virtual
machine. The IP address of the virtual machine accessible from outside is
`192.168.33.16`. If you start another Vagrant VM take into account that the IP
address will conflict, use `vagrant suspend` before starting another VM with the
same IP address.

The CLI is available in `../../bin`.
The CLI `../../bin/wsk` is for Linux amd64.
The CLI for all other operating systems and architectures (as well as Linux) can be
downloaded in a compressed format from [https://github.com/apache/openwhisk-cli/releases](https://github.com/apache/openwhisk-cli/releases) .
For more details, please consult the relevant [documentation](https://openwhisk.apache.org/documentation.html) section "Download and
install the wsk CLI from (Linux, Mac or Windows):".

When using the CLI with a local deployment of OpenWhisk (which provides an
insecure/self-signed SSL certificate), you must use the argument `-i` to permit
an insecure HTTPS connection to OpenWhisk. This should be used for development
purposes only.

Call the binary directly or setup your environment variable PATH to include the
location of the binary that corresponds to your environment.

From your _host_, configure `wsk` to use your Vagrant-hosted OpenWhisk
deployment and run the "echo" action again to test. The following commands
assume that you have `wsk` setup correctly in your PATH (and that you are using
Powershell if your deployment is hosted in Windows).
```
# Set your OpenWhisk Authorization Key.

wsk property set --apihost 192.168.33.16 --auth $(vagrant ssh -- cat openwhisk/ansible/files/auth.guest)

# Run the hello sample action
wsk -i action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

**Tip:**
You need to use the `-i` switch as the default SSL certificate used by the
Vagrant installation is self-signed. Alternatively, you can configure your
_apihost_ to use the non-SSL interface:

```
wsk property set --apihost http://192.168.33.16:10001 --auth $(vagrant ssh -- cat openwhisk/ansible/files/auth.guest)
```

You do not need to use the `-i` switch to `wsk` now. Note, however, that `wsk
sdk` will not work, so you need to pass use `wsk -i --apihost 192.168.33.16  sdk
{command}` in this case.


**Note:**
To connect to a different host API (i.e. openwhisk.example.com) with the CLI, you will
need to configure the CLI with new values for _apihost_, and _auth_ key.

### Use the wsk CLI inside the VM
For your convenience, a `wsk` wrapper is provided inside the VM which delegates
CLI commands to `$OPENWHISK_HOME/bin/linux/amd64/wsk` and adds the `-i`
parameter that is required for insecure access to the local OpenWhisk
deployment.

Calling the wsk CLI via `vagrant ssh` directly
```
vagrant ssh -- wsk action invoke /whisk.system/utils/echo -p message hello --result
```
Calling the wsk CLI by login into the Vagrant VM
```
vagrant ssh
wsk action invoke /whisk.system/utils/echo -p message hello --result
```

## Running OpenWhisk tests
```
vagrant ssh
cd ${OPENWHISK_HOME}
# run all tests
./gradlew tests:test
# or run a subset of tests using the --tests flag
./gradlew tests:test --tests system.basic.ConsoleTests
```

## Building OpenWhisk
Use Gradle to build docker images from inside the VM, this is done automatically
once at VM creation.

```
vagrant ssh
cd ${OPENWHISK_HOME}
./gradlew distDocker
```

## Using docker-runc
Only for experimental use:
To use docker-runc in the invoker, the version of docker-runc needs to match the version of docker engine on the host.
Get the version of the docker engine on the host like the following:
```
$ docker version | grep Version
Version:	18.03.0-ce
```
You need to use the same version for docker-runc in the Invoker, to use a newer version of docker-runc in the invoker, update the invoker Dockerfile.
1. compare the docker-runc version obtained on the local system against the docker-runc configured for the invoker
2. if the versions are different, only then do you need to update the invoker dockerfile to point to the matching docker download

Edit the [core/invoker/Dockefile](../../core/invoker/Dockefile)
Update the variable with the version
```
ENV DOCKER_VERSION 18.03.0-ce
```
Then update line with the curl download command like
```
RUN curl -sSL -o docker-${DOCKER_VERSION}.tgz https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz && \
```
Notice that the hostname where to download the CLI is different for newer versions.

Then update the Ansible configuration to enable the use of runc, edit [](../../ansible/environments/vagrant/group_vars/all)
```
invoker_use_runc: true
```

Then rebuild and redeploy the invoker component
```
wskdev invoker
```

### Teardown and Deploy
The following commands are helpful to deploy a fresh OpenWhisk and data store
```
vagrant ssh
cd ${ANSIBLE_HOME}
# teardown all containers
wskdev teardown
# deploy openwhisk containers
wskdev fresh
```
**Tip**
Do not restart the VM using Virtual Box tools, and always use `vagrant` from the
command line: `vagrant up` to start the VM and `vagrant reload` to restart it.
This allows the `$HOME/openwhisk` folder to be available inside the VM.

**Tip** If you have problems with data stores check that `ansible/db_local.ini` exists.

**Tip**
To initialize the data store from scratch run `ansible-playbook -i
environments/local initdb.yml` inside the VM as described in
[ansible setup](../../../ansible/README.md).

Once deployed, several Docker containers will be running in your virtual
machine. You can check that containers are running by using the docker cli with
the command `vagrant ssh -- docker ps`.

## Adding OpenWhisk users (Optional)

An OpenWhisk user, also known as a _subject_, requires a valid authorization
key. OpenWhisk is preconfigured with a guest key located in
`ansible/files/auth.guest`.

You may use this key if you like, or use [`wskadmin`](../admin) inside the VM to
create a new key.

```
vagrant ssh
wskadmin user create <subject>
```
For more information on `wskadmin` check the [documentation](../admin).

## SSL certificate configuration (Optional)

OpenWhisk includes a _self-signed_ SSL certificate and the `wsk` CLI allows
untrusted certificates via `-i` on the command line. The certificate is
generated during setup and stored in
`ansible/roles/nginx/files/openwhisk-cert.pem`.

Do not use these certificates in production: replace with your own and modify
the configuration to use trusted certificates instead.

## Misc commands
```
# Suspend Vagrant VM when done having fun
  vagrant suspend

# Resume Vagrant VM to have fun again
  vagrant up

# Do not restart via Virtual Box, use Vagrant reload to mount $HOME/openwhisk as a shared directory
  vagrant reload

# Read the help for wsk CLI
  vagrant ssh -- wsk -h
  vagrant ssh -- wsk <command> -h
```
**Tip**:
Don't use `vagrant resume`. See
[here](https://github.com/mitchellh/vagrant/issues/6787) for related issue.

## Override Vagrant Box
By default the Vagrant VM will use `ubuntu/xenial64` if you want to use `ubuntu/trusty64` you can override with an environment variable `BOX_OS`.
```
BOX_OS="ubuntu/trusty64" ./hello
```

## Using Vagrant VM in GUI mode (Optional)
Create VM with Desktop GUI. The `username` and `password` are both set to
`vagrant` by default.

```
  gui=true ./hello
  gui=true vagrant reload
```

**Tip**:
Ignore error message `Sub-process /usr/bin/dpkg returned an error code (1)` when
creating Vagrant VM using `gui-true`. Remember to use `gui=true` every time you
do `vagrant reload`. Or, you can enable the GUI directly by editing the Vagrant
file.

## Lean Setup
To have a lean setup (no Kafka, Zookeeper and no Invokers as separate entities)

Set environment variable LEAN to true before creating vagrant VM
```
export LEAN=true
```

