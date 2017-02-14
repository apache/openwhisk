# OpenWhisk CLI

OpenWhisk offers a powerfull command line interface that allows complete management of all aspects of the system.

## Setting up the OpenWhisk CLI 

- Building OpenWhisk from a cloned repository will result in the generation of the command line interface. The
generated CLIs will be located in `openwhisk/bin/go-cli/`. There will be an executable CLI located in the mentioned
directory that will run on the operating system, and CPU architecture on which it was built. Executables for other
operating system, and CPU architectures are located in the following directories: `openwhisk/bin/go-cli/mac`,
`openwhisk/bin/go-cli/linux`, `openwhisk/bin/go-cli/windows`.

- To download the CLI from an existing deployment, you will need to download the CLI using the deployment's base URL.
A list of downloadable CLIs for various operating systems, and CPU architectures can be obtained from the following
location `{BASE URL}/cli/go/download`. The `{BASE URL}` is the OpenWhisk API hostname or IP address
(e.g., openwhisk.ng.bluemix.net).

There are three properties to configure the CLI with:

1. **API host** (name or IP address) for the OpenWhisk deployment you want to use.
2. **Authorization key** (username and password) which grants you access to the OpenWhisk API.
3. **Namespace** where your OpenWhisk assets are stored.

The CLI will usually have an API host already set. You can check its value with
`wsk property get --apihost`.

If you know your authorization key and namespace, you can configure the CLI to use them. Otherwise
you will need to provide one or both for most CLI operations.

```
wsk property set [--apihost <openwhisk_baseurl>] --auth <username:password> --namespace <namespace>
```

The API host is set automatically when you build the CLI for your environment. A _guest_ account is available
in local installations with an authorization key located in [ansible/files/auth.guest](../ansible/files/auth.guest) and the namespace `guest`.
To configure the CLI to use the guest account, you can run the following command from your `openwhisk` directory:

```
./bin/wsk property set --namespace guest --auth `cat ansible/files/auth.guest`
```

To verify your CLI setup, try [creating and running an action](#openwhisk-hello-world-example).

## Setting up the deprecated OpenWhisk CLI (Python based)
- The OpenWhisk command line interface (CLI) requires Python 2.7.

- If you cloned the OpenWhisk repository, you will find the CLI in `openwhisk/bin/wsk`.

- Otherwise, download the CLI from an existing deployment. You will need to know the base URL for the deployment you
want to use and install it using [pip](https://pip.pypa.io/).

```
sudo pip install --upgrade https://{BASE URL}/openwhisk-0.1.0.tar.gz [--trusted-host {BASE URL}]
```

The `{BASE URL}` is the OpenWhisk API hostname or IP address (e.g., openwhisk.ng.bluemix.net).
The `--trusted-host` option allows you to download the CLI from a host with a [self-signed (i.e., untrusted) certificate](../tools/vagrant/README.md#ssl-certificate-configuration-optional).

## Using the OpenWhisk CLI

After you have configured your environment, you can begin using the OpenWhisk CLI to do the following:

* Run your code snippets, or actions, on OpenWhisk. See [Creating and invoking actions](./actions.md).
* Use triggers and rules to enable your actions to respond to events. See [Creating triggers and rules](./triggers_rules.md).
* Learn how packages bundle actions and configure external events sources. See [Using and creating packages](./packages.md).
* Explore the catalog of packages and enhance your applications with external services, such as a [Cloudant event source](./catalog.md#using-the-cloudant-package). See [Using OpenWhisk-enabled services](./catalog.md).

## Configure the CLI to use an HTTPS proxy

The CLI can be setup to use an HTTPS proxy. To setup an HTTPS proxy, an environment variable called `HTTPS_PROXY` must
 be created. The variable must be set to the address of the HTTPS proxy, and its port using the following format:
`{PROXY IP}:{PROXY PORT}`.
