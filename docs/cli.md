# OpenWhisk CLI

OpenWhisk offers a powerful command line interface that allows complete management of all aspects of the system.

## Setting up the OpenWhisk CLI 

- Building OpenWhisk from a cloned repository results in the generation of the command line interface. The generated CLIs are located in `openwhisk/bin/`. The main CLI is located in `openwhisk/bin/wsk` that runs on the operating system, and CPU architecture on which it was built. Executables for other operating system, and CPU architectures are located in the following directories: `openwhisk/bin/mac/`, `openwhisk/bin/linux/`, `openwhisk/bin/windows/`.

- To download the CLI from an existing deployment, you will need to download the CLI using the deployment's base URL.
A list of downloadable CLIs for various operating systems, and CPU architectures can be obtained from the following
location `{BASE URL}/cli/go/download`. The `{BASE URL}` is the OpenWhisk API hostname or IP address
(e.g., openwhisk.ng.bluemix.net).

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

**Tip:** The OpenWhisk CLI stores the properties set in `~/.wskprops` by default. The location of this file can be altered by setting the `WSK_CONFIG_FILE` environment variable.

To verify your CLI setup, try [creating and running an action](#openwhisk-hello-world-example).

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
* Run the follwing command to pass client certificate:
```
./bin/wsk property set --cert <client_cert_path> --key <client_key_path>
```
