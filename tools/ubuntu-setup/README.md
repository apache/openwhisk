# Setting up OpenWhisk on Ubuntu server

The following are verified to work on Ubuntu 14.04.3 LTS. You may need `sudo` or root access to install required software depending on your system setup.

  ```
  # Install git if it is not installed
  apt-get install git

  # Clone openwhisk
  git clone https://github.com/openwhisk/openwhisk.git
  
  # Change current directory to openwhisk
  cd openwhisk

  # Install all required software
  (cd tools/ubuntu-setup && source all.sh)
  ```

### Select one type of data store when creating vm
Follow instructions [tools/db/README.md](../db/README.md) on how to configure a datastore for OpenWhisk.

### Build and Deploy

```
# Build and deploy
  ant clean build deploy
  
# Or teardown and deploy
  ant redeploy
```
To teardown OpenWhisk and remove all Docker containers, run `ant teardown`. You can then redeploy the system with `ant deploy`. To do both at once, use `ant redeploy`.

**Tip** If you have problems with data stores check that `cloudant-local.env` or `couchdb-local.env` is present.
If the file `cloudant-local.env` is present the `couchdb-local.env` is ignored.

**Tip** To initialize the data store from scratch run `tools/db/createImmortalDBs.sh`

Once deployed, several Docker containers will be running in your virtual machine.
You can check that containers are running by using the docker cli with the command  `docker ps`

### Use the wsk CLI
```
bin/wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
{
    "message": "hello"
}
```

