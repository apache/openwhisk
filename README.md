# OpenWhisk

[![Build Status](https://travis-ci.org/openwhisk/openwhisk.svg?branch=master)](https://travis-ci.org/openwhisk/openwhisk)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

OpenWhisk is a cloud-first distributed event-based programming service. It provides a programming model to upload event handlers to a cloud service, and register the handlers to respond to various events. Learn more at https://developer.ibm.com/openwhisk or try it on [IBM Bluemix OpenWhisk](https://ibm.biz/openwhisk).


* [Quick Start](#quick-start)
* [Other Configurations](#custom-configurations)
* [Learn concepts and commands](#learn-concepts-and-commands)
* [License](#license)
* [Issues](#issues)

### Quick Start

A [Vagrant](http://vagrantup.com) machine is the easiest way to run OpenWhisk on Mac, Windows PC or GNU/Linux.
Download and install [Vagrant](https://www.vagrantup.com/downloads.html) for your operating system and architecture.

Follow these step to run your first OpenWhisk Action:
```
# Clone openwhisk
git clone --depth=1 https://github.com/openwhisk/openwhisk.git

# Change directory to tools/vagrant
cd openwhisk/tools/vagrant

# Run script to create vm and run hello action
./hello
```

Wait for hello action output:
```
wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
{
    "message": "hello"
}
```

For more information about OpenWhisk vagrant scenarios see the [tools/vagrant/README.md](tools/vagrant/README.md)

### Custom Configurations

The quick start above uses an ephemeral datastore that does not persist data when the vm is reloaded. The following instructions
allow you to configure OpenWhisk to use a persistent datastore via Cloudant or CouchDB.

These steps were tested on Mac OS X El Capitan, Ubuntu 14.04.3 LTS and Windows using Vagrant.

* [Vagrant on Mac, Windows PC, or GNU/Linux](tools/vagrant/README.md)
* [OpenWhisk on Ubuntu natively](tools/ubuntu-setup/README.md)

## Configure CLI

The OpenWhisk CLI is located in `/bin/wsk`. For convenience, you may configure it to use the built-in "guest" namespace and authorization key as follows:

```
./bin/wsk property set --namespace guest --auth `cat ansible/files/auth.guest`
```

This allows you to use the CLI without specifying the namespace and authorization key on every command.

### Learn concepts and commands

Browse the [documentation](docs/) to learn more. Here are some topics you may be
interested in:

- [System overview](docs/about.md)
- [Getting Started](docs/README.md)
- [Create and invoke actions](docs/actions.md)
- [Create triggers and rules](docs/triggers_rules.md)
- [Use and create packages](docs/packages.md)
- [Browse and use the catalog](docs/catalog.md)
- [Using the OpenWhisk mobile SDK](docs/mobile_sdk.md)
- [OpenWhisk system details](docs/reference.md)


### License

Copyright 2015-2016 IBM Corporation

Licensed under the [Apache License, Version 2.0 (the "License")](http://www.apache.org/licenses/LICENSE-2.0.html).

Unless required by applicable law or agreed to in writing, software distributed under the license is distributed on an "as is" basis, without warranties or conditions of any kind, either express or implied. See the license for the specific language governing permissions and limitations under the license.

### Issues

Report bugs, ask questions and request features [here on GitHub](../../issues).

You can also join our slack channel and chat with developers.   To get access to our slack channel, please see the instructions [here](https://github.com/openwhisk/openwhisk/wiki).
