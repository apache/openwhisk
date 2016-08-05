# Setting up OpenWhisk on Ubuntu server

The following are verified to work on Ubuntu 14.04.3 LTS. You may need `sudo` or root access to install required software depending on your system setup. 

If you are running OpenWhisk on a single VM/server, these commands need to be executed on that Ubuntu machine. If you are doing a dsitributed deployment spread across multiple machines, these commands need to be executed on machine which we call 'bootstrapper'. 

The bootstrapper machine could be your laptop, or it ideally is a Ubuntu 14.04 VM you provision in your IaaS, for e.g. OpenStack VM, which would have network connection to all the VMs we will provision for a distributed deployment.

  ```
  # Install git if it is not installed
  apt-get install git

  # Clone openwhisk
  git clone https://github.com/openwhisk/openwhisk.git
  
  # Change current directory to openwhisk
  cd openwhisk

  # Install all required software
  (cd tools/ubuntu-setup && source all.sh)
  ```

If you are deploying a distributed OpenWhisk across multiple VMs, follow the instructions in [ansible/README_DISTRIBUTED.md](../../ansible/README_DISTRIBUTED.md) to move further

### Select one type of data store when creating vm
Follow instructions [tools/db/README.md](../db/README.md) on how to configure a data store for OpenWhisk.

## Build

  ```
  cd <home_openwhisk>
  ./gradlew distDocker
  ```

## Deploy

If you want to deploy OpenWhisk locally in a single VM using Ansible, follow the instructions in [ansible/README.md](../../ansible/README.md) to deploy and teardown. 

Once deployed, several Docker containers will be running in your deployment.
You can check that containers are running by using the docker cli with the command  `docker ps`.

### Configure the CLI
Follow instructions in [Configure CLI](../../docs/README.md#setting-up-the-openwhisk-cli). The API host
should be `172.17.0.1` or more formally, the IP of the `edge` host from the
[ansible environment file](../../ansible/environments/local/hosts).

### Use the wsk CLI
```
bin/wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
{
    "message": "hello"
}
```

