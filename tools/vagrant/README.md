# OpenWhisk on Vagrant

The following instructions were tested on Mac OS X El Capitan, Ubuntu 14.04.3 LTS and Windows.

## Requirements
- Install [VirtualBox](https://www.virtualbox.org/wiki/Downloads) (tested with version 5.1.22)
- Install [Vagrant](https://www.vagrantup.com/downloads.html) (tested with version 1.9.5)

## Setup

### Clone the repository and change directory to `tools/vagrant`

```
git clone --depth=1 https://github.com/apache/incubator-openwhisk.git openwhisk
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

**Note:** Follow instructions [tools/db/README.md](../db/README.md) on how to configure the remote DB for OpenWhisk.

##### Option B.1 - Setting a remote Cloudant DB
```
# Provide credentials for cloudant database with admin permissions
OW_DB=cloudant OW_DB_USERNAME=xxxxx OW_DB_PASSWORD=yyyyyy ./hello
```

##### Option B.2 - Setting a remote CouchDB

```
# Provide credentials for couchdb database with admin permissions
OW_DB=couchdb OW_DB_USERNAME=xxxxx OW_DB_PASSWORD=yyyyyy OW_DB_PROTOCOL=http OW_DB_HOST=1.2.3.4 OW_DB_PORT=5984 ./hello
```

**Note:** Data will persist after [safe re-deploy](#safe-re-deploy-after-vm-restart), but will be destroyed if you initialze the DB.
For more information on data store configurations see [tools/db/README.md](../db/README.md).


### Wait for hello action output
```
wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```
**Tip:** The very first build may take 30 minutes or more depending on network speed.
If there are any build failures, it might be due to network timeouts, to recover follow the manual
process to build and deploy in [ansible/README.md](../../ansible/README.md)

**Tip:** By default, each `docker` command will timeout after 840 seconds (14 minutes). If you're on a really slow connection,
this might be too short. You can modify the timeout value in [docker.gradle](../../../gradle/docker.gradle#L22) as needed.

### Using CLI from outside the VM
You can use the CLI from the host machine as well as from inside the virtual machine.
The IP address of the virtual machine accessible from outside is `192.168.33.13`.
If you start another Vagrant VM take into account that the IP address will conflict, use `vagrant suspend` before starting another VM with the same IP address.

The CLI is available in `../../bin`. There you will find binaries specific to various operating systems and architectures (e.g. `../../bin/mac/amd64/wsk`).
When using the CLI with a local deployment of OpenWhisk (which provides an insecure/self-signed SSL certificate), you must use the argument `-i` to permit an insecure HTTPS connection to OpenWhisk. This should be used for development purposes only.

Call the binary directly or setup your environment variable PATH to include the location of the binary that corresponds to your environment.

From your _host_, configure `wsk` to use your Vagrant-hosted OpenWhisk deployment and run the "echo" action again to test.
The following commands assume that you have `wsk` setup correctly in your PATH.
```
# Set your OpenWhisk Authorization Key.
wsk property set --apihost 192.168.33.13 --auth `vagrant ssh -- cat openwhisk/ansible/files/auth.guest`

# Run the hello sample action
wsk -i action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

**Tip:** You need to use the `-i` switch as the default SSL certificate used by the Vagrant installation is self-signed. Alternatively, you can configure your __apihost__ to use the non-SSL interface:

```
wsk property set --apihost http://192.168.33.13:10001 --auth `vagrant ssh -- cat openwhisk/ansible/files/auth.guest`
```

You do not need to use the `-i` switch to `wsk` now. Note, however, that `wsk sdk` will not work, so you need to pass use `wsk -i --apihost 192.168.33.13  sdk {command}` in this case.


**Note:** To connect to a different host API (i.e. bluemix.net) with the CLI, you will need to configure the CLI with new values for __apihost__, and __auth__ key.

### Use the wsk CLI inside the VM
For your convenience, a `wsk` wrapper is provided inside the VM which delegates CLI commands to `$OPENWHISK_HOME/bin/linux/amd64/wsk` and adds the `-i` parameter that is required for insecure access to the local OpenWhisk deployment.

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
Use gradle to build docker images from inside the VM, this is done automatically once at VM creation.
```
vagrant ssh
cd ${OPENWHISK_HOME}
./gradlew distDocker
```

## Safe Re-deploy (after VM restart)

If you restart the VM (e.g., `vagrant reload`), it may be necessary to refresh the OpenWhisk deployment. You can do this in a way that does not reload the data store container.

```
vagrant ssh
cd ${ANSIBLE_HOME}
# teardown all containers expect couchdb container
ansible-playbook -i environments/local openwhisk.yml -e mode=clean
# deploy openwhisk containers
ansible-playbook -i environments/local openwhisk.yml
```

The following commands are helpful to deploy a fresh OpenWhisk and data store after booting a new VM using `vagrant up`.

### Teardown and Deploy (refresh the data store)
Use ansible to re-deploy OpenWhisk from inside the VM
To deploy a new code base you need to [re-build OpenWhisk](#build-openwhisk) first
```
vagrant ssh
cd ${ANSIBLE_HOME}
# teardown all deployed containers
ansible-playbook -i environments/local teardown.yml
# deploy couchdb container
ansible-playbook -i environments/local couchdb.yml
# initialize db with guest/system keys
ansible-playbook -i environments/local initdb.yml
# recreate main db for entities
ansible-playbook -i environments/local wipe.yml
# deploy openwhisk containers
ansible-playbook -i environments/local openwhisk.yml
# install catalog
ansible-playbook -i environments/local postdeploy.yml
```

**Tip** Do not restart the VM using Virtual Box tools, and always use `vagrant` from the command line: `vagrant up` to start the VM and `vagrant reload` to restart it. This allows the `$HOME/openwhisk` folder to be available inside the VM.

**Tip** If you have problems with data stores check that `ansible/db_local.ini`.

**Tip** To initialize the data store from scratch run `ansible-playbook -i environments/local initdb.yml` inside the VM as described in [ansible setup](../../../ansible/README.md).

Once deployed, several Docker containers will be running in your virtual machine.
You can check that containers are running by using the docker cli with the command `vagrant ssh -- docker ps`.


## Adding OpenWhisk users (Optional)

An OpenWhisk user, also known as a *subject*, requires a valid authorization key.
OpenWhisk is preconfigured with a guest key located in `ansible/files/auth.guest`.

You may use this key if you like, or use `wskadmin` inside the VM to create a new key.

```
vagrant ssh
wskadmin user create <subject>
```

This command will create a new *subject* with the authorization key shown on the console once you run `wskadmin`. This key is required when making API calls to OpenWhisk, or when using the command line interface (CLI). The namespace is the same as the `<subject>` name used to create the key.

A namespace allows two or more subjects to share resources. Each subject will have their own authorization key to work with resources in a namespace, but will have equal rights to the namespace.

```
vagrant ssh
wskadmin user create <subject> -ns <namespace>
```

The same tool may be used to remove a subject from a namespace or to delete a subject entirely.

```
vagrant ssh
wskadmin user delete <subject> -ns <namespace>  # removes <subject> from <namespace>
wskadmin user delete <subject>                   # deletes <subject>
```

## SSL certificate configuration (Optional)

OpenWhisk includes a _self-signed_ SSL certificate and the `wsk` CLI allows untrusted certificates via `-i` on the command line.
The certificate is generated during setup and stored in `ansible/roles/nginx/files/openwhisk-cert.pem`.

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
**Tip**: Don't use `vagrant resume`. See [here](https://github.com/mitchellh/vagrant/issues/6787) for related issue.

## Using Vagrant VM in GUI mode (Optional)
Create VM with Desktop GUI. The `username` and `password` are both set to `vagrant` by default.
```
  gui=true ./hello
  gui=true vagrant reload
```
**Tip**: Ignore error message `Sub-process /usr/bin/dpkg returned an error code (1)` when
creating Vagrant VM using `gui-true`. Remember to use `gui=true` every time you do `vagrant reload`.
Or, you can enable the GUI directly by editing the Vagrant file.
