Deploying Openwhisk using Ansible
=========


### Getting started

If you want to deploy Openwhisk locally using Ansible, you first need to install Ansible on your development environment:

#### Ubuntu users
```
sudo apt-get install python-pip
sudo pip install ansible==2.0.2.0
```

#### Vagrant users
Nothing to be done, Ansible is already installed during vagrant provisioning.
You can skip setup and prereq steps as those have been done by vagrant for you.  
You may jump directly to [Deploying Using CouchDB](#deploying-using-couchdb)

#### Mac users
It is assumed that a VM has been provisioned using [Docker Machine](../tools/macos/README.md).

```
brew install python
pip install ansible==2.0.2.0

cd ansible
ansible-playbook -i environments/mac setup.yml [-e docker_machine_name=whisk]
```

**Hint:** If you omit the optional `-e docker_machine_name` parameter, it will default to "whisk".  
If your docker-machine VM has a different name you may pass it via the `-e docker_machine_name` parameter.

After this there should be a `hosts` file in the `ansible/environments/mac` directory.

To verify the hosts file you can do a quick ping to the docker machine:

```
cd ansible
ansible all -i environments/mac -m ping
```

Should result in something like:

```
ansible | SUCCESS => {
    "changed": false,
    "ping": "pong"
}
192.168.99.100 | SUCCESS => {
    "changed": false,
    "ping": "pong"
}
```

### Using Ansible
**Caveat:** All ansible commands are meant to be executed from the `ansible` directory.
This is important because that's where `ansible.cfg` is located which contains generic settings that are needed for the remaining steps.

In all instructions, replace `<environment>` with your target environment. e.g. `mac`if you want to deploy using a local mac setup.
By default, if you omit the `-i` parameter, the `local` environment will be used.

In all instructions, replace `<openwhisk_home>` with the base directory of your Openwhisk source tree. e.g. `openwhisk`

#### Setup

This step needs to be done only once per development environment. It will generate configuration files based on your local settings. Notice that for the following playbook you don't need to specify a target environment as it will run only local actions.
After the playbook is done you should see a file called `db_local.ini` in your ansible directory. It will by default contain settings for a local ephemeral CouchDB setup. Afterwards, you can change the values directly in `db_local.ini` 

#####  Ephemeral CouchDB

If you want to use the ephemeral CouchDB, run this command

```
ansible-playbook -i environments/<environment> setup.yml
```

#####  Persistent CouchDB

If you want to use the persistent CouchDB instead, you can use env variables that are read by the playbook:

```
export OW_DB=CouchDB
export OW_DB_USERNAME=<your couchdb user>
export OW_DB_PASSWORD=<your couchdb password>
export OW_DB_PROTOCOL=<your couchdb protocol>
export OW_DB_HOST=<your couchdb host>
export OW_DB_PORT=<your couchdb port>

ansible-playbook -i environments/<environment> setup.yml
```

##### Cloudant

If you want to use Cloudant instead, you can use env variables that are read by the playbook:

```
export OW_DB=Cloudant
export OW_DB_USERNAME=<your cloudant user>
export OW_DB_PASSWORD=<your cloudant password>
export OW_DB_PROTOCOL=https
export OW_DB_HOST=<your cloudant user>.cloudant.com
export OW_DB_PORT=443

ansible-playbook -i environments/<environment> setup.yml
```

#### Install Prerequisites
This step needs to be done only once per target environment (in a local setup the development environment and the target environment are the same). It will install necessary prerequisites on all target hosts in the environment.

```
ansible-playbook -i environments/<environment> prereq.yml
```

**Hint:** During playbook execution the `TASK [prereq : check for pip]` can show as failed. This is normal if no pip is installed. The playbook will then move on and install pip on the target machines.

**Caveat:** Mac users who have a docker-machine setup will have to re-run this playbook every time the boot2docker machine is rebooted. This is because installed prereqs are not persisted on TinyCore Linux.


### Deploying Using CouchDB
- Make sure your `db_local.ini` file is set up for couchdb. See [Setup](#setup) 
- Then execute

```
cd <openwhisk_home>
gradle distDocker
cd ansible
ansible-playbook -i environments/<environment> couchdb.yml
ansible-playbook -i environments/<environment> initdb.yml
ansible-playbook -i environments/<environment> openwhisk.yml
```

You need to run `initdb.yml` on couchdb **every time** you deploy couchdb to initialize the database.


### Deploying Using Cloudant
- Make sure your `db_local.ini` file is set up for cloudant. See [Setup](#setup) 
- Then execute

```
cd <openwhisk_home>
gradle distDocker
cd ansible
ansible-playbook -i environments/<environment> initdb.yml
ansible-playbook -i environments/<environment> openwhisk.yml
```
You need to run `initdb` on cloudant **only once** per cloudant database to initialize the db.

**Hint:** The `initdb.yml` playbook will only initialize your database if it is not initialized already, else it will skip initialization steps.



### Hot-swapping a Single Component
The playbook structure allows you to clean, deploy or re-deploy a single component as well as the entire Openwhisk stack. Let's assume you have deployed the entire stack using the "openwhisk.yml" playbook. You then make a change to a single component, for example the invoker. You will probably want a new tag on the invoker image so you first build it using:

```
cd <openwhisk_home>
gradle :core:dispatcher:distDocker -PdockerImageTag=myNewInvoker
```
Then all you need to do is re-deploy the invoker using the new image:

```
cd ansible
ansible-playbook -i environments/<environment> invoker.yml -e docker_image_tag=myNewInvoker
```

**Hint:** You can omit the docker image tag parameters in which case `latest` will be used implicitly.

### Cleaning a Single Component
You can remove a single component just as you would remove the entire deployment stack.
For example, if you wanted to remove only the controller you would run:

```
cd ansible
ansible-playbook -i environments/<environment> controller.yml -e mode=clean
```

**Caveat:** In distributed environments some components (e.g. Consul, Invoker, etc.) exist on multiple machines. So if you run a playbook to clean or deploy those components, it will run on **all** of the hosts targeted by the component's playbook.


### Cleaning an Openwhisk Deployment
Once you are done with the deployment you can clean it from the target environment.

```
ansible-playbook -i environments/<environment> openwhisk.yml -e mode=clean
```

### Removing all prereqs from an environment
This is usually not necessary, however in case you want to uninstall all prereqs from a target environment, execute:

```
ansible-playbook -i environments/<environment> prereq.yml -e mode=clean
```


### Troubleshooting
Some of the more common problems and their solution are listed here.

#### Setuptools Version Mismatch
If you encounter the following error message during ansible execution

```
ERROR! Unexpected Exception: ... Requirement.parse('setuptools>=11.3'))
```

your setuptools package is probably outdated. To fix this, run this command:

```
pip install --upgrade setuptools --user python
```


#### Mac Setup - Python Interpreter
The MacOS environment makes the assumption that you installed python in /usr/local/bin using brew.
If you encounter the following error message during testing of your setup

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

your python installation is probably pointed somewhere else. To fix this run this command:

```
ln -s $(which python) /usr/local/bin/python
```

#### Spaces in Paths
Ansible does not like spaces in paths. There have been some efforts on fixing this issue but until now (read: ansible 2.1.0.0)
it is still a problem. Many file imports and roles don't work correctly when included from a path that contains spaces.
If you encounter this error message during ansible execution

```
fatal: [ansible]: FAILED! => {"failed": true, "msg": "need more than 1 value to unpack"}
```

the path to your Openwhisk ansible directory probably contains spaces. To fix this, please copy the source tree to a path
without spaces as there is no current fix available to this problem.
