# Setting up OpenWhisk on Ubuntu server(s)

The following are verified to work on Ubuntu 14.04.3 LTS. You may need `sudo` or root access to install required software depending on your system setup. 

The commands below should be executed on the host machine for single VM/server deployments of OpenWhisk. For a distributed deployment spanning multiple VMs, the commands should be executed on a machine with network connectivity to all the VMs in the deployment - this is called the `bootstrapper` and it is ideally an Ubuntu 14.04 VM that is provisioned in an IaaS (infrastructure as a service platform e.g., OpenStack).  Your local machine can act as the bootstrapper as well if it can connect to the VMs deployed in your IaaS.

  ```
  # Install git if it is not installed
  sudo apt-get install git -y

  # Clone openwhisk
  git clone https://github.com/apache/incubator-openwhisk.git openwhisk
  
  # Change current directory to openwhisk
  cd openwhisk

  # Install all required software
  (cd tools/ubuntu-setup && ./all.sh)
  ```

If you are deploying OpenWhisk in a distributed environment across multiple VMs, then follow the instructions in [ansible/README_DISTRIBUTED.md](../../ansible/README_DISTRIBUTED.md) to complete the deployment. Otherwise, continue with the instructions below.

### Select a data store
Follow instructions [tools/db/README.md](../db/README.md) on how to configure a data store for OpenWhisk.

## Build

  ```
  cd <home_openwhisk>
  ./gradlew distDocker
  ```

## Deploy

Follow the instructions in [ansible/README.md](../../ansible/README.md) to deploy and teardown OpenWhisk within a single machine or VM.

Once deployed, several Docker containers will be running in your machine.
You can check that containers are running by using the docker cli with the command `docker ps`.

### Configure the CLI
Follow instructions in [Configure CLI](../../docs/cli.md). The API host
should be `172.17.0.1` or more formally, the IP of the `edge` host from the
[ansible environment file](../../ansible/environments/local/hosts).

### Use the wsk CLI
```
bin/wsk action invoke /whisk.system/utils/echo -p message hello --result
{
    "message": "hello"
}
```

