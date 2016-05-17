OpenWhisk
=========

[![Build Status](https://travis-ci.org/openwhisk/openwhisk.svg?branch=master)](https://travis-ci.org/openwhisk/openwhisk)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

OpenWhisk is a cloud-first distributed event-based programming service. It provides a programming model to upload event handlers to a cloud service, and register the handlers to respond to various events. Learn more at https://developer.ibm.com/openwhisk or try it on [IBM Bluemix OpenWhisk](https://ibm.biz/openwhisk).


* [Getting Started](#getting-started)
* [Configure datastore](#configure-datastore)
* [Build and deploy](#build-and-deploy-openwhisk)
* [Configure your shell](#add-openwhisk-command-line-tools-to-your-path)
* [Add users](#add-openwhisk-users)
* [Setup CLI](#setup-cli)
* [Run sample action](#run-sample-action)
* [Learn concepts and commands](#learn-concepts-and-commands)
* [License](#license)
* [Issues](#issues)


### Getting started

The following instructions were tested on Mac OS X El Capitan, Ubuntu 14.04.3 LTS and may work on Windows using Vagrant.

* [Ubuntu users](#ubuntu-users)
* [Vagrant users on Mac and Windows PC](#vagrant-users-for-mac-and-windows)
* [Detailed instructions for Mac developers](#alternate-instructions-for-mac-developers)

#### Ubuntu users

The following are verified to work on Ubuntu 14.04.3 LTS. You may need `sudo` or root access to install required software depending on your system setup.

  ```
  # Install git if it is not installed
  apt-get install git

  # Clone openwhisk
  git clone https://github.com/openwhisk/openwhisk.git

  # Install all required software
  (cd openwhisk/tools/ubuntu-setup && source all.sh)
  ```

Once all the required software is installed, you can proceed to [configure](#configure-datastore) the datastore and [build](#build-and-deploy-openwhisk) OpenWhisk.

#### Vagrant users (for Mac and Windows)

A [Vagrant](http://vagrantup.com) machine is the easiest way to run OpenWhisk on Mac or Windows PC. You can download the Vagrantfile and then follow the instructions for installing OpenWhisk on Ubuntu.

  ```
  # Fetch Vagrantfile
  wget https://raw.githubusercontent.com/openwhisk/openwhisk/master/tools/vagrant/Vagrantfile

  # Start virtual machine
  vagrant up

  # Login
  vagrant ssh

  # Follow instructions for Ubuntu users

  # When all steps are completed, logout
  logout

  # Reload Vagrant machine to complete setup
  vagrant reload
  ```

Login to the virtual machine either using the VirtualBox terminal or with `vagrant ssh`. The login username is `vagrant` and the password is `vagrant`. You can proceed to [configure](#configure-datastore) the datastore and [build](#build-and-deploy-openwhisk) OpenWhisk in your Vagrant machine.

**Tip:** The Vagrant file is configured to allocate a virtual machine with a recommended 4GB of RAM.

#### Alternate instructions for Mac developers

Mac users can clone, build and deploy OpenWhisk either with a Vagrant or Docker machine. The following detail how to install OpenWhisk with Vagrant using a shared file system so that you can develop OpenWhisk natively on the Mac but deploy it inside a virtual machine.

  ```
  git clone https://github.com/openwhisk/openwhisk.git
  cd openwhisk
  ```

**Install [Vagrant](http://vagrantup.com) then complete the following from a terminal.**

  ```
  cd tools/vagrant
  ENV=shared vagrant up
  ENV=shared vagrant reload
  ```

**Tip:** If the Vagrant machine provisioning fails, you can rerun it with `ENV=shared vagrant provision`. Alternatively, rerun the following from _inside_ the Vagrant virtual machine.

  ```
  (cd openwhisk/tools/ubuntu-setup && source all.sh)
  ```

**Tip:** Optionally, if you want to use Ubuntu desktop `(cd openwhisk/tools/ubuntu-setup && source ubuntu-desktop.sh)`

**Your virtual machine is now ready to build and run OpenWhisk. _Login to the virtual machine to complete the next steps._**

### Configure datastore

Before you can build and deploy OpenWhisk, you must configure a backing datastore. The system supports any self-managed CouchDB instance or
[Cloudant](https://cloudant.com) as a cloud-based database service.

#### Using CouchDB

If you are using your own installation of CouchDB, make a note of the host, port, username and password. Then within your `openwhisk` directory, copy the file `template-couchdb-local.env` to `couchdb-local.env` and edit as appropriate. Note that:

  * the username must have administrative rights
  * the CouchDB instance must be accessible over `http` or `https` (the latter requires a valid certificate)
  * the CouchDB instance must set `reduce_limit` on views to `false` (see [this](tools/db/couchdb/createAdmin.sh#L55) for how to do this via REST)
  * make sure you do not have a `cloudant-local.env` file, as it takes precedence over the CouchDB configuration

##### Using an ephemeral CouchDB container

To try out OpenWhisk without managing your own CouchDB installation, you can start a CouchDB instance in a container as part of the OpenWhisk deployment. We advise that you use this method only as a temporary measure. Please note that:

  * no data will persist between two creations of the container
  * you will need to run the creation script every time you `clean` or `teardown` the system (see below)
  * you will need to initialize the datastore each time (`tools/db/createImmportalDBs.sh`, see below)

```
  # Work out of your openwhisk directory
  cd /your/path/to/openwhisk

  # Start a CouchDB container and create an admin account
  tools/db/couchdb/start-couchdb-box.sh whisk_admin some_passw0rd

  # The script above automatically creates couchdb-local.env
  cat couchdb-local.env
```

#### Using Cloudant

As an alternative to a self-managed CouchDB, you may want to try Cloudant which is a cloud-based database service. There are two ways to get a Cloudant account and configure OpenWhisk to use it. You only need to establish an account once, either through IBM Bluemix or with Cloudant directly. Once you have created a Cloudant account, make note of the account `username` and `password`. Then within your `openwhisk` directory, copy the file `template-cloudant-local.env` to `cloudant-local.env` and edit as appropriate.

##### Create a Cloudant account via IBM Bluemix
Sign up for an account via [IBM Bluemix](https://bluemix.net). Bluemix offers trial accounts and its signup process is straightforward so it is not described here in detail. Using Bluemix, the most convenient way to create a Cloudant instance is via the `cf` command-line tool. See [here](https://www.ng.bluemix.net/docs/starters/install_cli.html) for instructions on how to download and configure `cf` to work with your Bluemix account.

When `cf` is set up, issue the following commands to create a Cloudant database.

  ```
  # Create a Cloudant service
  cf create-service cloudantNoSQLDB Shared cloudant-for-openwhisk

  # Create Cloudant service keys
  cf create-service-key cloudant-for-openwhisk openwhisk

  # Get Cloudant username and password
  cf service-key cloudant-for-openwhisk openwhisk
  ```

Make note of the Cloudant `username` and `password` from the last `cf` command so you can create the required `cloudant-local.env`.

##### Create a Cloudant account directly with Cloudant

As an alternative to IBM Bluemix, you may sign up for an account with [Cloudant](https://cloudant.com) directly. Cloudant is free to try and offers a metered pricing where the first $50 of usage is free each month. The signup process is straightforward so it is not described here in detail.

#### Initializing database for authorization keys

The system requires certain authorization keys to install standard assets (i.e., samples) and provide guest access for running unit tests.
These are called immortal keys. If you are using a persisted datastore (e.g., Cloudant), you only need to perform this operation _once_.
If you are using an ephemeral CouchDB container, you need to run this script every time you tear down and deploy the system.

  ```
  # Work out of your openwhisk directory
  cd /your/path/to/openwhisk
  
  # Initialize datastore containing authorization keys
  tools/db/createImmortalDBs.sh
  ```

The script will ask you to confirm this database initialization.

  ```
  About to drop and recreate database 'subjects' in this Cloudant account:
  <cloudant username>
  This will wipe the previous database if it exists and this is not reversible.
  Respond with 'DROPIT' to continue and anything else to abort.
  Are you sure?
  ```

Confirm initialization by typing `DROPIT`. The output should resemble the following.

  ```
  subjects
  curl --user ... -X DELETE https://<cloudant-username>.cloudant.com/subjects
  {"error":"not_found","reason":"Database does not exist."}
  curl --user ... -X PUT https://<cloudant-username>.cloudant.com/subjects
  {"ok":true}
  {"ok":true,"id":"_design/subjects","rev":"1-..."}
  Create immortal key for guest ...
  {"ok":true,"id":"guest","rev":"1-..."}
  Create immortal key for whisk.system ...
  {"ok":true,"id":"whisk.system","rev":"1-..."}
  ```

### Build and deploy OpenWhisk

Once you have created and configured one of `cloudant-local.env` or `couchdb-local.env` _and_ initialized the datastore, you are ready to build and deploy OpenWhisk. The following commands are relative to the OpenWhisk directory. If necessary, change your working directory with `cd /your/path/to/openwhisk`.

  ```
  # Build and deploy OpenWhisk in the virtual machine
  ant clean build deploy

  # Optionally run the unit tests against your local deployment
  ant run
  ```

**Tip:** If during the steps above it appears some required software (e.g. `ant`) is missing, run the machine provisioning again and capture the output to see if some installation step failed.

**Tip:** The first build will take some time as it fetches many dependencies from the Internet. The duration of this step can range from 25 minutes to an hour or more depending on your network speed. Once deployed, several Docker containers will be running in your virtual machine.

**Tip:** Since the first build takes some time, it is not uncommon for some step to fail if there's a network hiccup or other interruption of some kind. If this happens you may see a `Build FAILED` message that suggests a Docker operation timed out. You can simply try `ant build` again and it will mostly pick up where it left off. This should only be an issue the very first time you build -- subsequent builds do far less network activity thanks to Docker caching.

**Tip:** By default, each `docker` command will timeout after 840 seconds (14 minutes). If you're on a really slow connection, this might be too short. You can modify the timeout value in [`docker.gradle`](https://github.com/openwhisk/openwhisk/blob/master/docker.gradle#L22) as needed.

To teardown OpenWhisk and remove all Docker containers, run `ant teardown`. You can then redeploy the system with `ant deploy`. To do both at once, use `ant redeploy`.

### Add OpenWhisk command line tools to your path

The OpenWhisk command line tools are located in the `openwhisk/bin` folder. The instructions that follow assume `/your/path/to/openwhisk/bin` is in your system `PATH`. If you are using a Vagrant machine in a shared configuration (Mac users), the OpenWhisk command line tools are already in your path inside the virtual machine.

See the script in `openwhisk/tools/ubuntu-setup/bashprofile.sh` if you need help or to see how to add tab completion for the OpenWhisk CLI. _Do not source or run this script if you have your own `.bash_profile` as it will overwrite it._

**Tip:** The command line tools require Python 2.7. If you are using OpenWhisk from an unsupported system, make sure you have this version of Python available.

### Add OpenWhisk users

An OpenWhisk user, also known as a *subject*, requires a valid authorization key.
OpenWhisk is preconfigured with a guest key located in `config/keys/auth.guest`.

You may use this key if you like, or use `wskadmin` to create a new key.

  ```
  wskadmin user create <subject>
  ```

This command will create a new *subject* with the authorization key shown on the console once you run `wskadmin`. This key is required when making API calls to OpenWhisk, or when using the command line interface (CLI).

The same tool may be used to delete a subject.

  ```
  wskadmin user delete <subject>
  ```

### Setup CLI

When using the CLI `wsk`, it is convenient to store the authorization key in a property file so that one does not have to supply the key on every command.

OpenWhisk is preconfigured with a guest key. You can use this key if you like, or use `wskadmin` to create a new key.

1. Configuring the CLI to use the guest authorization key:
  ```
  wsk property set --auth $(cat config/keys/auth.guest)
  ```

2. **Or** to create a new user and set the authorization key:
  ```
  wsk property set --auth $(wskadmin user create <subject>)
  ```

**Tip:** The `wsk` CLI offers tab completion on commands and parameters. Hit tab to complete a command or to see available commands and arguments for a given context.

**Tip:** You can use tab completion outside the virtual machine as well since the `wsk` CLI is available on the host as well. Install [argcomplete](https://github.com/kislyuk/argcomplete) with `sudo pip install argcomplete` and add this to your Bash profile
`eval "$(register-python-argcomplete wsk)"`.

### Run sample action

You are now ready to run your first action. Try the "echo" action. It returns its input as the result of the action.

  ```
  wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
  ```

should produce the following output:
  ```
  {
     "message": "hello"
  }
  ```


### Using CLI from outside Vagrant machine

If you cloned OpenWhisk natively onto a Mac and using a Vagrant machine to host an OpenWhisk deployment, then you can use the CLI from the host machine as well as from inside the virtual machine.

By default, the virtual machine IP address is `192.168.33.13` (see Vagrant file). From your _host_, configure `wsk` to use your Vagrant-hosted OpenWhisk deployment and run the "echo" action again to test.

  ```
  wsk property set --apihost 192.168.33.13 --auth <auth key>

  wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
  {
    "message": "hello"
  }
  ```

### SSL certificate

OpenWhisk includes a _self-signed_ SSL certificate and the `wsk` CLI allows untrusted certificates.

  ```
  ls config/keys/openwhisk-self-*
  config/keys/openwhisk-self-cert.pem
  config/keys/openwhisk-self-key.pem
  ```

These are configured in `config/localEnv.sh`

  ```
  #
  # SSL certificate used by router
  #
  WHISK_SSL_CERTIFICATE=config/keys/openwhisk-self-cert.pem
  WHISK_SSL_KEY=config/keys/openwhisk-self-key.pem
  WHISK_SSL_CHALLENGE=openwhisk
  ```

Do not use these certificates in production: add your own and modify
the configuration to use trusted certificates instead


### Learn concepts and commands

Browse the [documentation](docs/) to learn more. Here are some topics you may be
interested in:

- [System overview](docs/about.md)
- [Create and invoke actions](./docs/actions.md)
- [Create triggers and rules](./docs/triggers_rules.md)
- [Use and create packages](./docs/packages.md)
- [Browse and use the catalog](./docs/catalog.md)


### License

Copyright 2015-2016 IBM Corporation

Licensed under the [Apache License, Version 2.0 (the "License")](http://www.apache.org/licenses/LICENSE-2.0.html).

Unless required by applicable law or agreed to in writing, software distributed under the license is distributed on an "as is" basis, without warranties or conditions of any kind, either express or implied. See the license for the specific language governing permissions and limitations under the license.

### Issues

Report bugs, ask questions and request features [here on GitHub](../../issues).
