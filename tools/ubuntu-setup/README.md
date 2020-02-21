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

# Setting up OpenWhisk on Ubuntu server(s)

The following are verified to work on Ubuntu 18.04. You may need `sudo` or root access to install required software depending on your system setup.

The commands below should be executed on the host machine for single VM/server deployments of OpenWhisk.
For a distributed deployment spanning multiple VMs, the commands should be executed on a machine with network connectivity to all the VMs in the deployment - this is called the `bootstrapper` and it is ideally an Ubuntu 18.04 VM that is provisioned in an IaaS (infrastructure as a service platform).
Your local machine can act as the bootstrapper as well if it can connect to the VMs deployed in your IaaS.

  ```
  # Install git if it is not installed
  sudo apt-get install git -y

  # Clone openwhisk
  git clone https://github.com/apache/openwhisk.git openwhisk

  # Change current directory to openwhisk
  cd openwhisk
  ```

Open JDK 8 is installed by running the following script as the default Java environment.

  ```
  # Install all required software
  (cd tools/ubuntu-setup && ./all.sh)
  ```

If you choose to install Oracle JDK 8 instead of Open JDK 8, please run the following script.

  ```
  # Install all required software
  (cd tools/ubuntu-setup && ./all.sh oracle)
  ```

### Select a data store
Follow instructions [tools/db/README.md](../db/README.md) on how to configure a data store for OpenWhisk.

## Build

  ```
  cd <home_openwhisk>
  ./gradlew distDocker
  ```
If your build fails with 'Exception in thread "main" javax.net.ssl.SSLException: java.lang.RuntimeException: Unexpected error: java.security.InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty', you might need to run 'sudo update-ca-certificates -f'.

## Deploy

Follow the instructions in [ansible/README.md](../../ansible/README.md) to deploy and teardown OpenWhisk within a single machine or VM.

Once deployed, several Docker containers will be running in your machine.
You can check that containers are running by using the docker CLI with the command `docker ps`.

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

