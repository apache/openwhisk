## Custom setup for OpenWhisk using Vagrant

The following instructions were tested on Mac OS X El Capitan, Ubuntu 14.04.3 LTS and may work on Windows.

*Requirements*
- Install [Vagrant](https://www.vagrantup.com/downloads.html)
 
### Clone the repository and change directory to `tools/vagrant/custom`
```
git clone https://github.com/openwhisk/openwhisk.git
cd openwhisk/tools/vagrant/custom
```

### Create vm using custom datastore (Cloudant or CouchDB)
Follow instructions [tools/db/README.md](../../db/README.md) on how to configure a datastore for OpenWhisk.

#### Option 1: Create VM using Cloudant DB
```
# Provide credentials for cloudant database with admin permissions
OW_DB=cloudant OW_DB_USERNAME=xxxxx OW_DB_PASSWORD=yyyyyy vagrant up
```

#### Option 2: Create vm using persistent CouchDB
```
# Provide credentials for couchdb database with admin permissions
OW_DB=couchdb OW_DB_USERNAME=xxxxx OW_DB_PASSWORD=yyyyyy OW_DB_PROTOCOL=http OW_DB_HOST=1.2.3.4 OW_DB_PORT=5984 vagrant up
```

#### Option 3: Create vm using ephemeral CouchDB
**Important** We advise that you use this method only as a temporary measure. 
Please note that no data will persist between two creations of the container.
For more information on datastore configurations see [tools/db/README.md](../../db/README.md).
```
# Configure with couchdb docker container running inside the vm 
  vagrant up
```

### Wait for hello action output
```
wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
{
    "message": "hello"
}
```
**Tip:** The very first build may take 10 minutes or more depending on network speed. 
If there are any build failures, it might be due to network timeouts, run `../resume_build` on the host.


**Tip:** By default, each `docker` command will timeout after 840 seconds (14 minutes). If you're on a really slow connection,
this might be too short. You can modify the timeout value in [docker.gradle](../../../docker.gradle#L22) as needed.

### Use the wsk CLI inside the vm
```
vagrant ssh -- wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
```
**Tip:** Login into the vm using `vagrant ssh` then use the `wsk` CLI with tab completion on commands and parameters. 
Hit tab to complete a command or to see available commands and arguments for a given context.


### Running tests
```
vagrant ssh -- ant run
```

### Build and Deploy

When making changes, rebuild and deploy the system with:
```
# Build and deploy
  vagrant ssh -- ant clean build deploy
  
# Or teardown and deploy
  vagrant ssh -- ant redeploy
```
To teardown OpenWhisk and remove all Docker containers, run `vagrant ssh -- ant teardown`. You can then redeploy the system with `ant deploy`. To do both at once, use `ant redeploy`.

**Tip** If you have problems with data stores check that `cloudant-local.env` or `couchdb-local.env` is present (inside the vm).
If the file `cloudant-local.env` is present the `couchdb-local.env` is ignored.

**Tip** To initialize the data store from scratch run `../tools/db/createImmortalDBs.sh` inside the vm. Note that using an ephemeral CouchDB (Option 3) requires alternate steps.

Once deployed, several Docker containers will be running in your virtual machine.
You can check that containers are running by using the docker cli with the command `vagrant ssh -- docker ps`.


### Adding OpenWhisk users (Optional)

An OpenWhisk user, also known as a *subject*, requires a valid authorization key and namespace.
OpenWhisk is preconfigured with a guest key located in `config/keys/auth.guest`.
The default namespace is __guest__.

You may use this key if you like, or use `wskadmin` inside the vm to create a new key.

```
wskadmin user create <subject>
```

This command will create a new *subject* with the authorization key shown on the console once you run `wskadmin`. This key is required when making API calls to OpenWhisk, or when using the command line interface (CLI). The namespace is the same as the `<subject>` name used to create the key.

The same tool may be used to delete a subject.

```
wskadmin user delete <subject>
```
  
### Using CLI from outside the vm (Optional)
If you cloned OpenWhisk natively onto a Mac and using a Vagrant machine to host an OpenWhisk deployment, then you can use the CLI from the host machine as well as from inside the virtual machine.
To find out the IP address of the virtual machine you can run ssh to get the value.
```
vagrant ssh -- ip route get 8.8.8.8 | awk '{print $NF; exit}'
10.0.2.15
```

From your _host_, configure `wsk` to use your Vagrant-hosted OpenWhisk deployment and run the "echo" action again to test.
```
# Set your OpenWhisk Namespace and Authorization Key.
  wsk property set --apihost `vagrant ssh -- ip route get 8.8.8.8 | awk '{print $NF; exit}'` --namespace guest --auth `vagrant ssh -- cat openwhisk/config/keys/auth.guest`

# Run the hello sample action
  wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
  {
    "message": "hello"
  }
```
**Tip:** To connect to a different host API (i.e. bluemix.net) with the CLI, you will need to 
configure the CLI with new values for __apihost__, __namespace__, and __auth__ key.
  

### SSL certificate configuration (Optional)

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
the configuration to use trusted certificates instead.


### Misc
```
# Suspend vagrant vm when done having fun
  vagrant suspend

# Resume vagrant vm to have fun again
  vagrant up

# Read the help for wsk CLI
  vagrant ssh -- wsk -h
  vagrant ssh -- wsk <command> -h
```
**Tip**: Don't use `vagrant resume`. See [here](https://github.com/mitchellh/vagrant/issues/6787) for related issue.

### Using Vagrant vm in GUI mode (Optional)
Create vm with Desktop GUI. The `username` and `password` are both set to `vagrant` by default.
```
  gui=true ./hello
  gui=true vagrant reload
```
**Tip**: Ignore error message `Sub-process /usr/bin/dpkg returned an error code (1)` when 
creating Vagrant vm using `gui-true`. Remember to use `gui=true` everytime you do `vagrant reload`.
Or, you can enable the GUI directly by editing the `Vagrant` file.
