OpenWhisk
==========

OpenWhisk is a cloud-first distributed event-based programming service. It provides a programming model to upload event handlers to a cloud service, and register the handlers to respond to various events. Learn more at https://developer.ibm.com/openwhisk or try it on [IBM Bluemix OpenWhisk](https://ibm.biz/openwhisk).


* [Getting Started](#getting-started)
* [Configure OpenWhisk](#clone-and-configure-openwhisk)
* [Build and deploy with Vagrant](#build-and-deploy-with-vagrant)
* [Add users](#add-users)
* [Setup CLI](#setup-cli)
* [Run sample action](#run-sample-action)
* [Learn concepts and commands](#learn-concepts-and-commands)
* [License](#license)
* [Issues](#issues)



### Getting started

The following instructions were tested on Mac OSX and may work on Ubuntu. You cannot build and run OpenWhisk on Windows yet.

Before you can use OpenWhisk, you must configure a backing datastore. Currently, the system uses [Cloudant](https://cloudant.com) as a cloud-based database service. We are working on offering alternative backing stores.

There are two ways to get a Cloudant account and configure OpenWhisk to use it. You only need to establish an account once, either through IBM Bluemix or with Cloudant directly. Once you have created a Cloudant account, make note of the account `username` and `password`.

#### Create a Cloudant account via IBM Bluemix
Sign up for an account via [IBM Bluemix](https://bluemix.net). Bluemix offers trial accounts and its signup process is straightforward so it is not described here in detail.

Once you have established a Bluemix account, the most convenient way to create a Cloudant instance is via the `cf` command-line tool.
See [here](https://www.ng.bluemix.net/docs/starters/install_cli.html) for instructions on how to download and configure `cf` to
work with your Bluemix account.

Once you've set up `cf`, issue the following commands to create a Cloudant database to use for OpenWhisk:

  ```
  # Create a Cloudant service
  cf create-service cloudantNoSQLDB Shared cloudant-for-openwhisk
  
  # Create Cloudant service keys
  cf create-service-key cloudant-for-openwhisk openwhisk

  # Get Cloudant service keys
  cf service-key cloudant-for-openwhisk openwhisk
  ```

#### Create a Cloudant account directly with Cloudant

As an alternative to IBM Bluemix, you may sign up for an account on [Cloudant](https://cloudant.com) directly. Cloudant is free to try and offers a metered pricing where the first $50 of usage is free each month. The signup process is straightforward so it is not described here in detail.

### Clone and configure OpenWhisk

Clone the OpenWhisk repository into your workspace.

  ```
  git clone https://github.com/openwhisk/openwhisk.git
  cd openwhisk
  ```

Before you build and deploy the system, you must configure it to use your Cloudant account.

1. Copy the file named `template-cloudant-local.env` to `cloudant-local.env`.

  ```
  cp template-cloudant-local.env cloudant-local.env
  ```

2. Open the file `cloudant-local.env` and fill in your Cloudant account credentials.

3. Next, you must initialize the datastore. This creates a database to hold user authorization keys, including a guest user key (used for running unit tests) and a system key for installing standard OpenWhisk assets (e.g., samples).

  ```
  tools/cloudant/createImmortalDBs.sh <cloudant username> <cloudant password>
  ```

The script will ask you to confirm this database initialization.

  ```
  About to drop and recreate database 'subjects' in this cloudant account:
  <cloudant username>
  This will wipe the previous database if it exists and this is not reversible.
  Respond with 'DROPIT' to continue and anything else to abort.
  Are you sure? 
  ```

Confirm initialization by typing `DROPIT`. The output should resemble the following.

  ```
  subjects
  curl --user ... -X DELETE https://<cloudant username>.cloudant.com/subjects
  {"error":"not_found","reason":"Database does not exist."}
  curl --user ... -X PUT https://<cloudant username>.cloudant.com/subjects
  {"ok":true}
  {"ok":true,"id":"_design/subjects","rev":"1-..."}
  Create immortal key for guest ...
  {"ok":true,"id":"guest","rev":"1-..."}
  Create immortal key for whisk.system ...
  {"ok":true,"id":"whisk.system","rev":"1-..."}
  ```

### Build and deploy with Vagrant

Install Vagrant from [vagrantup.com](http://vagrantup.com). Then, from a terminal:

  ```
  cd tools/vagrant
  vagrant up
  vagrant reload
  ```

Login to the virtual machine either using the VirtualBox terminal or with `vagrant ssh`. The login username is `vagrant` and the password is `vagrant`.

**Tip:** The Vagrant file is configured to allocate a virtual machine with a recommended 4GB of RAM. 

**Tip:** If the Vagrant machine provisioning fails, you can rerun it with `vagrant provision`. Alternatively, rerun the following from _inside_ the Vagrant virtual machine.

  ```
  (cd openwhisk/tools/ubuntu-setup && source all.sh)
  ```

**Tip:** Optionally, if you want to use Ubuntu desktop `(cd openwhisk/tools/ubuntu-setup && source ubuntu-desktop.sh)`

#### Build OpenWhisk in Vagrant virtual machine

Your virtual machine is now ready to build and run OpenWhisk. _Login to the virtual machine to complete the next steps._

  ```
  # all commands are relative to the OpenWhisk directory in your Vagrant machine
  cd openwhisk
  
  # build and deploy OpenWhisk in the virtual machine
  ant clean build deploy
  
  # optionally run the unit tests against your local deployment
  ant run
  ```

**Tip:** If during the steps above it appears some required software (e.g. `ant`) is missing, run the machine provisioning again and capture the output to see if some installation step failed.

**Tip:** The first build will take some time as it fetches many dependencies from the Internet. The duration of this step can range from 25 minutes to an hour or more depending on your network speed. Once deployed, several Docker containers will be running in your virtual machine.

**Tip:** Since the first build takes some time, it is not uncommon for some step to fail if there's a network hiccup or other interruption of some kind. If this happens you may see a `Build FAILED` message that suggests a Docker operation timed out. You can simply try `ant build` again and it will mostly pick up where it left off. This should only be an issue the very first time you build whisk -- subsequent builds do far less network activity thanks to Docker caching.

### Add users

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

OpenWhisk is preconfigured with a guest key. You can use this key if you like, or use
`wskadmin` to create a new key.

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

The OpenWhisk deployment within the Vagrant virtual machine is accessible from the host machine. By default, the virtual machine
IP address is `192.168.33.13` (see Vagrant file). From your _host_, configure `wsk` to use your Vagrant based OpenWhisk API deployment and run the "echo" action again to test.

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
