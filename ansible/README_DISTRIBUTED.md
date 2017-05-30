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
ansible-playbook -i environments/<environment> openwhisk.yml
ansible-playbook -i environments/<environment> postdeploy.yml
```

Setup your CLI and verify that OpenWhisk is working.

```
../bin/wsk property set --auth $(cat files/auth.whisk.system) --apihost <edge_url>
../bin/wsk -i -v action invoke /whisk.system/samples/helloWorld --blocking --result
```
