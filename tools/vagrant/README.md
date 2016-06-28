## Custom setup for OpenWhisk using Vagrant

The following instructions were tested on Mac OS X El Capitan, Ubuntu 14.04.3 LTS and may work on Windows.

*Requirements*
- Install [Vagrant](https://www.vagrantup.com/downloads.html)
 
### Clone the repository and change directory to `tools/vagrant`
```
git clone --depth=1 https://github.com/openwhisk/openwhisk.git
cd openwhisk/tools/vagrant
```

### Create a Vagrant VM

#### Option 1: Create VM using ephemeral CouchDB
**Important** We advise that you use this method for development of OpenWhisk.
Please note that no data will persist between two creations of the container.
For more information on datastore configurations see [tools/db/README.md](../../db/README.md).
```
# Configure with couchdb docker container running inside the VM
./hello
```

Follow instructions [tools/db/README.md](../../db/README.md) on how to configure a datastore for OpenWhisk.

#### Option 2: Create VM using Cloudant DB
```
# Provide credentials for cloudant database with admin permissions
OW_DB=cloudant OW_DB_USERNAME=xxxxx OW_DB_PASSWORD=yyyyyy ./hello
```

#### Option 3: Create VM using persistent CouchDB
```
# Provide credentials for couchdb database with admin permissions
OW_DB=couchdb OW_DB_USERNAME=xxxxx OW_DB_PASSWORD=yyyyyy OW_DB_PROTOCOL=http OW_DB_HOST=1.2.3.4 OW_DB_PORT=5984 ./hello
```

### Wait for hello action output
```
wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
{
    "message": "hello"
}
```
**Tip:** The very first build may take 30 minutes or more depending on network speed. 
If there are any build failures, it might be due to network timeouts, to recover follow the manual
process to build and deploy in [ansible/README.md](../../ansible/README.md)

**Tip:** By default, each `docker` command will timeout after 840 seconds (14 minutes). If you're on a really slow connection,
this might be too short. You can modify the timeout value in [docker.gradle](../../../docker.gradle#L22) as needed.

### Using CLI from outside the VM
You can use the CLI from the host machine as well as from inside the virtual machine.
The IP address of the virtual machine accesible from outside is `192.168.33.13`.
If you start another Vagrant VM take into account that the IP address will conflict, use `vagrant suspend` before starting another VM with the same IP address.

After the Vagrant VM is done deploying OpenWhisk, the `wsk` CLI will be available under `<openwhisk>/bin`
We currently have two types for the `wsk` CLI, Python and Go. 

The Python CLI is avaible in `../../bin/wsk`. 
For the Python CLI you can configure autocomplete by adding `eval "$(register-python-argcomplete wsk)"` in your `~/.bash_profile` or `~/.profile`

The Go CLI is available in `../../bin/go-cli` there are multiple binaries base on OS and Architecture (i.e. `../../bin/go-cli/mac/amd64/wsk`).
When using the Go CLI, use the argument `-i` to be able to connect insecurely to OpenWhisk for development purpose only.

Cal the binary directly or setup your environment variable PATH to include the location of the binary that corresponds to your environment.

From your _host_, configure `wsk` to use your Vagrant-hosted OpenWhisk deployment and run the "echo" action again to test.
The following commands assume that you have `wsk` setup correctly in your PATH.
```
# Set your OpenWhisk Namespace and Authorization Key.
  wsk property set --apihost 192.168.33.13 --namespace guest --auth `vagrant ssh -- cat openwhisk/ansible/files/auth.guest`

# Run the hello sample action
  wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
  {
    "message": "hello"
  }
```
**Tip:** To connect to a different host API (i.e. bluemix.net) with the CLI, you will need to 
configure the CLI with new values for __apihost__, __namespace__, and __auth__ key.
 
### Use the wsk CLI inside the VM
Calling the wsk CLI via `vagrant ssh` directly
```
vagrant ssh -- wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
```
Calling the wsk CLI by login into the Vagrant VM
```
vagrant ssh
wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
```



### Running tests
```
vagrant ssh
gradle tests:test
```

### Build
Use gradle to build docker images from inside the VM
```
vagrant ssh
cd openwhisk
gradle distDocker
```

### Deploy (ansible)
Use ansible to re-deploy OpenWhisk from inside the VM
```
vagrant ssh
cd openwhisk/ansible
ansible-playbook -i environments/local openwhisk.yml -e mode=clean
ansible-playbook -i environments/local openwhisk.yml
```

**Tip** If you have problems with data stores check that `ansible/db_local.ini`.

**Tip** To initialize the data store from scratch run `ansible-playbook -i environments/local initdb.yml` inside the VM as described in [ansible setup](../../../ansible/README.md).

Once deployed, several Docker containers will be running in your virtual machine.
You can check that containers are running by using the docker cli with the command `vagrant ssh -- docker ps`.


### Adding OpenWhisk users (Optional)

An OpenWhisk user, also known as a *subject*, requires a valid authorization key and namespace.
OpenWhisk is preconfigured with a guest key located in `ansible/files/auth.guest`.
The default namespace is __guest__.

You may use this key if you like, or use `wskadmin` inside the VM to create a new key.

```
vagrant ssh
openwhisk/bin/wskadmin user create <subject>
```

This command will create a new *subject* with the authorization key shown on the console once you run `wskadmin`. This key is required when making API calls to OpenWhisk, or when using the command line interface (CLI). The namespace is the same as the `<subject>` name used to create the key.

The same tool may be used to delete a subject.

```
vagrant ssh
openwhisk/bin/wskadmin user delete <subject>
```
  
 

### SSL certificate configuration (Optional)

OpenWhisk includes a _self-signed_ SSL certificate and the `wsk` CLI allows untrusted certificates.

```
ls ansible/roles/nginx/files/openwhisk-*
ansible/roles/nginx/files/openwhisk-cert.pem
ansible/roles/nginx/files/openwhisk-key.pem
```

Do not use these certificates in production: replace with your own and modify
the configuration to use trusted certificates instead.


### Misc
```
# Suspend Vagrant VM when done having fun
  vagrant suspend

# Resume Vagrant VM to have fun again
  vagrant up

# Read the help for wsk CLI
  vagrant ssh -- wsk -h
  vagrant ssh -- wsk <command> -h
```
**Tip**: Don't use `vagrant resume`. See [here](https://github.com/mitchellh/vagrant/issues/6787) for related issue.

### Using Vagrant VM in GUI mode (Optional)
Create VM with Desktop GUI. The `username` and `password` are both set to `vagrant` by default.
```
  gui=true ./hello
  gui=true vagrant reload
```
**Tip**: Ignore error message `Sub-process /usr/bin/dpkg returned an error code (1)` when 
creating Vagrant VM using `gui-true`. Remember to use `gui=true` every time you do `vagrant reload`.
Or, you can enable the GUI directly by editing the Vagrant file.
