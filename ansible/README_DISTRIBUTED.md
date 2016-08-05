Deploying Distributed OpenWhisk using Ansible
=========

### Distributed Deployment using Ansible
**Caveat:** All Ansible commands are meant to be executed from the `ansible` directory.
This is important because that's where `ansible.cfg` is located which contains generic settings that are needed for the remaining steps.

By default, if you omit the `-i` parameter in ansible commands, the `local` environment will be used.

In all instructions, replace `<openwhisk_home>` with the base directory of your OpenWhisk source tree. e.g. `openwhisk`

Login to your bootsrapper VM. Your local machine can act as bootstrapper as well, if it can connect to other VMs.

Install prereqs for Ansible Cloud Module packages
```
sudo apt-get -y install python-setuptools python-dev libssl-dev
```

Install Ansible Cloud Module packages:
```
sudo pip install shade pytz positional appdirs monotonic rfc3986
```

#### Distributed Deployment using OpenStack as IaaS 

```
sudo apt-get install python-novaclient
sudo pip install six --upgrade
```
If you would like the environment instances and hosts file to be generated and managed by ansible, set values for the following keys using environment variables. These values can be pulled from the Openstack UI (https://${openstack_dashboard_url}/project/access_and_security/) as an rc file

```
export OS_NET_NAME=network_name
export OS_NET_ID=e489dcf2-4601-4809-a459-e3821a95d23a
export OS_IMAGE=Ubuntu14.04-1Nic
export OS_FLAVOR=m1.medium
export OS_USERNAME=abcxyz
export OS_PASSWORD=*******
export OS_PROJECT_NAME=OpenWhisk
export OS_KEY_NAME=key_name
export OS_SECURITY_GROUPS=sec_group

## Keystone v2
export OS_AUTH_URL=https://OpenStack_URL:5000/v2.0
export OS_TENANT_ID=a9e6a61ab914455cb4329592d5733325
export OS_TENANT_NAME="OpenWhisk"

## Keystone v3
export OS_AUTH_URL=https://OpenStack_URL:5000/v3
export OS_PROJECT_ID=a9e6a61ab914455cb4329592d5733325
export OS_USER_DOMAIN_NAME="domain"
```

- Set a value for the default ssh user in the defaults section of the ansible.cfg file
```
[defaults]
remote_user = ubuntu
```

- Change the "deployment" value in the ansible/group_vars/alli config file from "prod" to "open"

Then, run the following to boot instances and generate the hosts file
```
ansible-playbook -i environments/distributed provision_env_dist.yml
```

If you would like to manually manage the openstack instances/hosts file instead, continue by provisioning at least 8 VMs which will serve as openwhisk nodes, and ensure they are accessible at port 22. 

Place ips of openwhisk VMs into a hosts file using the following as a guide https://github.com/openwhisk/openwhisk/blob/master/ansible/environments/distributed/hosts. 

Do not add the "ansible_connection=local" parameter for remote hosts, else all playbook commands against that host will be executed locally on the ansible VM instead of the target

Ensure that the ansible vm can authenticate to the openwhisk VMs via SSH using the following command. If using a private key that is not in the default ~/.ssh folder, either add the parameter "--keyfile=/path/to/file.pem" to each ansible playbook command, or add "private_key_file=/path/to/file.pem" to ansible.cfg in the openwhisk/ansible directory.

```
ansible all -i environments/distributed -m ping
```

Install prereqs on all nodes
```
ansible-playbook -i environments/distributed prereq_build.yml
```

Deploy registry
```
ansible-playbook -i environments/distributed registry.yml
```

Build and distribute whisk docker images
```
cd ../
gradlew distDocker -PdockerHost=<registry_vm_ip>:4243 -PdockerRegistry=<registry_vm_ip>:5000
```
Now run the following steps shared from a [single VM](README.md) deployment. 

Deploy couchdb and configure whisk deployment
```
cd ansible
ansible-playbook -i environments/<environment> couchdb.yml
ansible-playbook -i environments/<environment> initdb.yml
ansible-playbook -i environments/<environment> wipe.yml
ansible-playbook -i environments/<environment> openwhisk.yml
ansible-playbook -i environments/<environment> postdeploy.yml
```

Set credentials and test whisk endpoint using cli
```
../bin/wsk  property set --auth $(cat files/auth.whisk.system) --apihost <edge_url>
../bin/wsk -v action invoke /whisk.system/samples/echo -p message hello --blocking --result
```
