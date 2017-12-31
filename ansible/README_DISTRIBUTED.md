Deploying Distributed OpenWhisk using Ansible
=========

**Caveat:** All Ansible commands are meant to be executed from the `ansible` directory.
This is important because that's where `ansible.cfg` is located which contains generic settings that are needed for the remaining steps.

By default, if you omit the `-i` parameter in Ansible commands, the `local` environment will be used.

In all instructions, replace `<openwhisk_home>` with the base directory of your OpenWhisk source tree. e.g., `openwhisk`.


#### Setup and provision OpenWhisk component VMs
A set of Ubuntu 14.04 machines will need to be provisioned in the targeted IaaS (Infrastructure as a Service platform). These VMs will need to provisioned manually in most IaaS providers, but we have added some scripts to automate VM/disk provisioning against Openstack CPIs. These scripts are not being actively maintained at the moment, but PRs to enhance the scripts and add support for other IaaS offerings (AWS, GCE, etc) are certainly encouraged. Once the VMs are up and reachable by the bootstapper VM, the installation process should be the same regardless of provider.

If using Openstack, please follow the README at [environments/distributed/files/openstack/README_OS.md](environments/distributed/files/openstack/README_OS.md) to manage the required VMs. Otherwise, provision each VM manually.

Login to your bootstrapper VM. Your local machine can act as the bootstrapping machine as well, as long as it can connect to the VMs deployed in your IaaS.

Add the remote_user and private_key_file values to the defaults section of the `ansible.cfg` file. The remote_user value sets the default ssh user. The private_key_file is required when using a private key that is not in the default `~/.ssh` folder.

```
[defaults]
remote_user = ubuntu
private_key_file=/path/to/file.pem
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

Build and distribute OpenWhisk docker images. Must be executed with root privileges. The IP for the registry VM can be found in the [hosts](environments/distributed/hosts) file.

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
ansible-playbook -i environments/<environment> apigateway.yml
ansible-playbook -i environments/<environment> openwhisk.yml
ansible-playbook -i environments/<environment> postdeploy.yml
```

Setup your CLI and verify that OpenWhisk is working.

```
../bin/wsk property set --auth $(cat files/auth.whisk.system) --apihost <edge_url>
../bin/wsk -i -v action invoke /whisk.system/samples/helloWorld --blocking --result
```

## AWS

### Authentication

To run the provisioning you will need to set up your AWS credentials locally, either with the 
[`awscli`](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-welcome.html) or by setting environment variables:

``` 
export AWS_ACCESS_KEY_ID='<your access key>'
export AWS_SECRET_ACCESS_KEY='<your secret access key>'
```

Note, if you want to create a new IAM user for Openwhisk work, the only permissions they need are `AmazonEC2FullAccess`.

### Configuration

There are some default configuration options set in `roles/aws/defaults/main.yml` but you can customise these to suit
your needs.

The main configuration is held in `ansible/environments/distributed/group_vars/all`. This file contains a list of 
instance specs in the `instances` variable. The provisioning playbook will use this list to create the EC2 instances.

Each configuration has a `name` and `num_instances` property. For example, if we wanted to have 3 invokers on 
`t2.medium` EC2 instances, we would set: 

```
instances:
  ...
  
  - name: invokers
    num_instances: 3
    flavor: t2.medium 
```

You can also add a `volume` dictionary to specify the volume that will be attached to the instance, for example:

```
instances:

  ...
  
  - name: db
    num_instances: 1
    flavor: t2.medium
    volume:
      name: /dev/xvdb
      size: 10
      fstype: ext4
      fsmount: /mnt/db
```

When setting up volumes, note the [available device names](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/device_naming.html) 
on EC2 instances. There is also some more general information on using EBS volumes 
[here](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ebs-using-volumes.html).

Before starting you will also need to update your `ansible/ansible.cfg` file with your `remote_user` and 
`private_key_file`. Note that the `private_key_file` should match that set in `ansible/roles/aws/defaults/main.yml`.
With the defaults this will be:

```
[defaults]
remote_user = ubuntu
private_key_file = <your home dir>/.ssh/openwhisk_key.pem" 

...
```

### Registry volume size

The `registry` instance requires quite a lot of disk space so it's advisable to add a large volume, e.g.

```
- name: registry
  num_instances: 1
  flavor: t2.medium
  volume:
    name: /dev/sda1
    size: 100
    fsmount: /
```

Note that here `/dev/xvda` is the default root device for this instance type. This may vary depending on 
the EC2 instance type you're using (e.g. `/dev/xvda`)

### Provisioning

To run the provisioning you need to execute the following (from the `ansible` directory as mentioned above):

```
ansible-playbook -i environments/distributed provision_aws.yml
```

This will set up the relevant VMs along with a security group. It will also template the inventory file and group
vars files found at `environments/distributed/hosts` and `environments/distributed/group_vars` respectively.


