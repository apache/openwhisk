Deploying Distributed OpenWhisk using Ansible
=========

**Caveat:** All Ansible commands are meant to be executed from the `ansible` directory.
This is important because that's where `ansible.cfg` is located which contains generic settings that are needed for the remaining steps.

By default, if you omit the `-i` parameter in Ansible commands, the `local` environment will be used.

In all instructions, replace `<openwhisk_home>` with the base directory of your OpenWhisk source tree. e.g., `openwhisk`.

Login to your bootstrapper VM. Your local machine can act as the bootstrapping machine as well, if it can connect to the VMs deployed in your IaaS (Infrastructure as a Service platform).

#### Distributed Deployment using OpenStack as IaaS

This installs modules and packages to manage cloud instances via Ansible.

```
sudo apt-get -y install python-setuptools python-dev libssl-dev build-essential libssl-dev libffi-dev python-dev python-novaclient
sudo pip install shade pytz positional appdirs monotonic rfc3986 pyparsing stevedore debtcollector netaddr oslo.config futures warlock six
```
If you would like the environment instances and hosts file to be generated and managed by Ansible, set values for the following keys using environment variables. Some of these values can be pulled from the Openstack UI (`https://${openstack_dashboard_url}/project/access_and_security/`) as an [RC](http://docs.openstack.org/user-guide/common/cli-set-environment-variables-using-openstack-rc.html) file.

Please note that OS_WSK_DB_VOLUME is optional. If not specified, local disk will be used instead of persistent disk for CouchDB.

```
export OS_FLAVOR=m1.medium
export OS_IMAGE=Ubuntu14.04-1Nic
export OS_KEY_NAME=key_name
export OS_NET_NAME=network_name
export OS_NET_ID=e489dcf2-4601-4809-a459-e3821a95d23a
export OS_USERNAME=abcxyz
export OS_PASSWORD=*******
export OS_PROJECT_NAME=OpenWhisk
export OS_SECURITY_GROUPS=sec_group
export OS_WSK_DB_VOLUME=15

## Keystone v2
export OS_AUTH_URL=https://OpenStack_URL:5000/v2.0
export OS_TENANT_NAME="OpenWhisk"
export OS_TENANT_ID=a9e6a61ab914455cb4329592d5733325

## Keystone v3
export OS_AUTH_URL=https://OpenStack_URL:5000/v3
export OS_PROJECT_ID=a9e6a61ab914455cb4329592d5733325
export OS_USER_DOMAIN_NAME="domain"
```
#### Setup and provision OpenWhisk component VMs

Add the remote_user and private_key_file values to the defaults section of the `ansible.cfg` file. The remote_user value sets the default ssh user. The private_key_file is required when using a private key that is not in the default `~/.ssh` folder.

```
[defaults]
remote_user = ubuntu
private_key_file=/path/to/file.pem
```

By default, 2 invokers are created. To adjust this value, simply change the num_instances value in the [environments/distributed/group_vars/all](environments/distributed/group_vars/all:67) file.

- Run the following playbook to boot instances and generate the respective hosts file.
```
ansible-playbook -i environments/distributed provision_env_dist.yml
```

Ensure that the Ansible VM can authenticate to the OpenWhisk VMs via SSH using the following command.

```
ansible all -i environments/distributed -m ping
```

Generate config files

```
ansible-playbook -i environments/distributed setup.yml
```

Install prerequisites on OpenWhisk nodes.

```
ansible-playbook -i environments/distributed prereq_build.yml
```

#### Build and deploy OpenWhisk

Deploy registry.

```
ansible-playbook -i environments/distributed registry.yml
```

Build and distribute OpenWhisk docker images. Must be executed with root privileges.

```
cd ..
./gradlew distDocker -PdockerHost=<registry_vm_ip>:4243 -PdockerRegistry=<registry_vm_ip>:5000
```
Now run the following steps. These are equivalent to a [single VM](README.md) deployment.

Deploy CouchDB and configure OpenWhisk deployment.

```
cd ansible
ansible-playbook -i environments/<environment> couchdb.yml
ansible-playbook -i environments/<environment> initdb.yml
ansible-playbook -i environments/<environment> wipe.yml
ansible-playbook -i environments/<environment> openwhisk.yml
ansible-playbook -i environments/<environment> postdeploy.yml
```

Setup your CLI and verify that OpenWhisk is working.

```
../bin/wsk property set --auth $(cat files/auth.whisk.system) --apihost <edge_url>
../bin/wsk -i -v action invoke /whisk.system/samples/helloWorld --result
```
