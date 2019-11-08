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
# OpenWhisk CLI

OpenWhisk offers a powerful command line interface that allows complete management of all aspects of the system.

## Setting up the OpenWhisk CLI

- Building OpenWhisk from a cloned repository results in the generation of the command line interface. The generated CLIs are located in `openwhisk/bin/`. The main CLI is located in `openwhisk/bin/wsk` that runs on the operating system, and CPU architecture on which it was built. Executables for other operating system, and CPU architectures are located in the following directories: `openwhisk/bin/mac/`, `openwhisk/bin/linux/`, `openwhisk/bin/windows/`.

- To download the CLI from an existing deployment, you will need to download the CLI using the deployment's base URL.
A list of downloadable CLIs for various operating systems, and CPU architectures can be obtained from the following
location `{BASE URL}/cli`. The `{BASE URL}` is the OpenWhisk API hostname or IP address.

There are two required properties to configure in order to use the CLI:

1. **API host** (name or IP address) for the OpenWhisk deployment you want to use.
2. **Authorization key** (username and password) which grants you access to the OpenWhisk API.

The API host can be acquired from the `edge.host` property in `whisk.properties` file, which is generated during
deployment of OpenWhisk. Run the following command from your `openwhisk` directory to set the API host:

```
./bin/wsk property set --apihost <openwhisk_baseurl>
```

**Tip:** If you are using a local OpenWhisk deployment with a self-signed SSL certificate, you can use `--insecure` to bypass certificate validation.

If you know your authorization key, you can configure the CLI to use it. Otherwise, you will need to obtain an
authorization key for most CLI operations. A _guest_ account is available in local installations with an authorization
key located in [ansible/files/auth.guest](../ansible/files/auth.guest). To configure the CLI to use the guest account,
you can run the following command from your `openwhisk` directory:

```
./bin/wsk property set --auth `cat ansible/files/auth.guest`
```

**Tip:** The OpenWhisk CLI stores properties in the `~/.wskprops` configuration file by default. The location of this file can be altered by setting the `WSK_CONFIG_FILE` environment variable.

The required properties described above have the following keys in the `.wskprops` file:

- **APIHOST** - Required key for the API host value.
- **AUTH** - Required key for the Authorization key.

To verify your CLI setup, try [creating and running an action](./samples.md).

### Optional Whisk Properties

Some OpenWhisk providers make use of optional properties that can be added to the `.wskprops` file.  The following keys are optional:

- **APIGW_ACCESS_TOKEN** - Optional, provider-specific authorization token for an independently hosted API Gateway service used for managing OpenWhisk API endpoints.

- **APIGW_TENANT_ID** - Optional, provider-relative identifier of the tenant (owner for access control purposes) of any API endpoints that are created by the CLI.

### Configure command completion for Openwhisk CLI

For bash command completion to work, bash 4.1 or newer is required. The most recent Linux distributions should have the correct version of bash but Mac users will most likely have an older version.

Mac users can check their bash version and update it by running the following commands:

```
bash --version
brew install bash
```

This requires [Homebrew](https://brew.sh/) to be installed. The updated bash will be installed in `/usr/local/bin`.

To write the bash command completion to your local directory, run the following command:

```
wsk sdk install bashauto
```
The command script `wsk_cli_bash_completion.sh` will now be in your current directory. To enable command line completion of wsk commands, source the auto completion script into your bash environment.

```
source wsk_cli_bash_completion.sh
```

Alternatively, to install bash command completion, run the following command:

```
eval "`wsk sdk install bashauto --stdout`"
```

For Mac users, autocomplete doesn't insert space after using TAB. To workaround this, you need to modify the output script like the following:
```
eval "`wsk sdk install bashauto --stdout | sed 's/-o nospace//'`"
```

**Note:** Every time a new terminal is opened, this command must run to enable bash command completion. Alternatively, adding the previous command to the `.bashrc` or `.profile` will prevent this.

## Using the OpenWhisk CLI

After you have configured your environment, you can begin using the OpenWhisk CLI to do the following:

* Run your code snippets, or actions, on OpenWhisk. See [Creating and invoking actions](./actions.md).
* Use triggers and rules to enable your actions to respond to events. See [Creating triggers and rules](./triggers_rules.md).
* Learn how packages bundle actions and configure external events sources. See [Using and creating packages](./packages.md).
* Explore the catalog of packages and enhance your applications with external services, such as a [Cloudant event source](./catalog.md#using-the-cloudant-package). See [Using OpenWhisk-enabled services](./catalog.md).

## Configure the CLI to use an HTTPS proxy

The CLI can be setup to use an HTTPS proxy. To setup an HTTPS proxy, an environment variable called `HTTPS_PROXY` must be created. The variable must be set to the address of the HTTPS proxy, and its port using the following format:
`{PROXY IP}:{PROXY PORT}`.

## Configure the CLI to use client certificate
The CLI has an extra level of security from client to apihost, system provides default client certificate configuration which deployment process generated, then you can refer to below steps to use client certificate:
* The client certificate verification is off default, you can configure `nginx_ssl_verify_client` to `on` or `optional` to open it for your corresponding environment configuration.
* Create your own client certificate instead of system provides if you want, after created, you can configure `openwhisk_client_ca_cert` to your own ca cert path for your corresponding environment configuration.
* Run the following command to pass client certificate:
```
./bin/wsk property set --cert <client_cert_path> --key <client_key_path>
```
