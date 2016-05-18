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

#### Mac users
It is assumed that a VM has been provisioned using **docker-machine**.

```
brew install python
pip install ansible==2.0.2.0

cd ansible
ansible-playbook setup.yml [-e docker_machine_name=whisk]
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


#### Install Prerequisites
This needs to be done only once per environment. It will install necessary prerequisites on all target hosts in the environment.

```
ansible-playbook -i environments/<environment> prereq.yml
```

**Hint:** During playbook execution the `TASK [prereq : check for pip]` can show as failed. This is normal if no pip is installed. The playbook will then move on and install pip on the target machines.

**Caveat:** Mac users who have a docker-machine setup will have to re-run this playbook every time the boot2docker machine is rebooted. This is because installed prereqs are not persisted on TinyCore Linux.


### Deploying Using CouchDB
- Make sure the couchdb section is **uncommented** in `environments/<environment>/group_vars/all`
- Make sure the cloudant section is **commented out**
- Then execute

```
cd <openwhisk_home>
gradle distDocker
cd ansible
ansible-playbook -i environments/<environment> couchdb.yml
ansible-playbook -i environments/<environment> couchdb.yml -e mode=initdb
ansible-playbook -i environments/<environment> openwhisk.yml
```

You need to run "initdb" on couch **every time** you deploy couchdb to initialize the db.

### Deploying Using Cloudant
- Make sure the cloudant section is **uncommented** in in `environments/<environment>/group_vars/all`
- Make sure the couchdb section is **commented out**
- Enter your cloudant credentials (db_username, db_password) in the cloudant section
- Then execute

```
cd <openwhisk_home>
gradle distDocker
cd ansible
ansible-playbook -i environments/<environment> cloudant.yml -e mode=initdb
ansible-playbook -i environments/<environment> openwhisk.yml
```
You need to run "initdb" on cloudant **only once** per cloudant database to initialize the db.

**Hint:** In either case the `initdb` mode will ask for your confirmation to drop the database. To move on you need to enter `CTRL-C + C`. To abort you need to enter `CTRL-C + A`. You can skip this confirmation step by adding `-e prompt_user=false` to the command:

```
ansible-playbook -i environments/<environment> couchdb.yml -e mode=initdb -e prompt_user=false
```

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
