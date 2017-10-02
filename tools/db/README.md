# Configure data store

Before you can build and deploy OpenWhisk, you must configure a backing data store. The system supports any self-managed [CouchDB](#using-couchdb) instance or [Cloudant](#using-cloudant) as a cloud-based database service.

## Using CouchDB

If you are using your own installation of CouchDB, make a note of the host, port, username and password. Then provision a [custom Vagrant box](../vagrant/README.md) by following the instructions for a persistent CouchDB. In case you already have a Vagrant box, maybe using default settings you can simply adjust the existing db settings. Within your `openwhisk/ansible` directory, edit the file `db_local.ini` as appropriate. If you do not find `db_local.ini`, refer to [Setup](../../ansible/README.md#setup) to create it. Note that:

   * the username must have administrative rights
   * the CouchDB instance must be accessible over `http` or `https` (the latter requires a valid certificate)
   * the CouchDB instance must set `reduce_limit` on views to `false` (see [this](../../ansible/README.md#persistent-couchdb) for how to do this via REST)

### Using an ephemeral CouchDB container

To try out OpenWhisk without managing your own CouchDB installation, you can start a CouchDB instance in a container as part of the OpenWhisk deployment. We advise that you use this method only as a temporary measure. Please note that:

  * no data will persist between two creations of the container
  * you will need to run `ansible-playbook couchdb.yml` every time you `clean` or `teardown` the system (see below)
  * you will need to initialize the data store each time (`ansible-playbook initdb.yml`, see below)

Detailed instructions are found in the [ansible readme](../../ansible/README.md).

## Using Cloudant

As an alternative to a self-managed CouchDB, you may want to try [Cloudant](https://cloudant.com) which is a cloud-based database service. 
There are two ways to get a Cloudant account and configure OpenWhisk to use it. 
You only need to establish an account once, either through IBM Bluemix or with Cloudant directly. 

### Create a Cloudant account via IBM Bluemix
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

Make note of the Cloudant `username` and `password` from the last `cf` command so you can create the required `db_local.ini`.

### Create a Cloudant account directly with Cloudant

As an alternative to IBM Bluemix, you may sign up for an account with [Cloudant](https://cloudant.com) directly. Cloudant is free to try and offers a metered pricing where the first $50 of usage is free each month. The signup process is straightforward so it is not described here in detail.
Once you have created a Cloudant account, make note of the account `username` and `password` from the Cloudant dashboard, so you can create the required `db_local.ini`.

### Setting the Cloudant credentials

Provision a [custom Vagrant box](../vagrant/README.md) by following the instructions for Cloudant.

If you already have an existing box, you can simply modify the settings in the VM. Within your `openwhisk/ansible` directory, edit the file `db_local.ini` as appropriate.

Note that:

   * the protocol for Cloudant is always HTTPS
   * the port is always 443
   * the host has the schema `<your cloudant user>.cloudant.com`

More details on customizing `db_local.ini` are described in the [ansible readme](../../ansible/README.md).

## Initializing database for authorization keys

The system requires certain authorization keys to install standard assets (i.e., samples) and provide guest access for running unit tests.
These are called immortal keys. If you are using a persisted data store (e.g., Cloudant), you only need to perform this operation **once**.
If you are [using an ephemeral CouchDB container](#using-an-ephemeral-couchdb-container), you need to run this script every time you tear down and deploy the system.

  ```
  # Work out of your openwhisk directory
  cd /your/path/to/openwhisk/ansible
  
  # Initialize data store containing authorization keys
  ansible-playbook initdb.yml
  ```

The playbook will create the required data structures to prepare the account to be used.
Don't worry if you are unsure whether or not the db has already been initialized. The playbook won't perform any action on a db that is already prepared.

The output of the playbook will look similar to this (using CouchDB in this example):

  ```
  PLAY [ansible] *****************************************************************

  TASK [setup] *******************************************************************
  Tuesday 14 June 2016  16:33:51 +0200 (0:00:00.017)       0:00:00.017 **********
  ok: [ansible]

  TASK [include] *****************************************************************
  Tuesday 14 June 2016  16:33:51 +0200 (0:00:00.262)       0:00:00.280 **********
  included: /your/path/to/openwhisk/ansible/tasks/initdb.yml for ansible

  TASK [check if the immortal subjects db with CouchDB exists?] ******************
  Tuesday 14 June 2016  16:33:51 +0200 (0:00:00.060)       0:00:00.340 **********
  ok: [ansible]

  TASK [create immortal subjects db with CouchDB] ********************************
  Tuesday 14 June 2016  16:33:51 +0200 (0:00:00.329)       0:00:00.670 **********
  ok: [ansible]

  TASK [recreate the "full" index on the "auth" database] ************************
  Tuesday 14 June 2016  16:33:52 +0200 (0:00:00.166)       0:00:00.837 **********
  ok: [ansible]

  TASK [recreate necessary "auth" keys] ******************************************
  Tuesday 14 June 2016  16:33:52 +0200 (0:00:00.162)       0:00:01.000 **********
  ok: [ansible] => (item=guest)
  ok: [ansible] => (item=whisk.system)

  PLAY RECAP *********************************************************************
  ansible                    : ok=6    changed=0    unreachable=0    failed=0
  ```

## Database backups

Backups are essential for running a production system of any sort and size. `replicateDbs.py` provides an easy to use interface that uses [CouchDBs replication mechanism](https://wiki.apache.org/couchdb/Replication) to create *snapshot replications*, *continuous replications* and a mechanism to play a snapshot back into the production system.

All commands for `replicateDbs.py` take two standard parameters:

* `--sourceDbUrl`: Server URL of the source database, that has to be backed up. E.g. 'https://xxx:yyy@domain.couch.com:443'.
* `--targetDbUrl`: Server URL of the target database, where the backup is stored. Like sourceDbUrl.

### Creating a snapshot

To create a snapshot, call `replicateDbs.py` with the `replicate` command. It takes 3 parameters:

* `--dbPrefix`: The prefix of all databases that should be backed up.
* `--expires`: Removes all snapshots older than the provided amount of seconds.
* `--continuous`: If specified, the created replication will be continuous.

Using that command will result in a replication for every database that matches the `--dbPrefix` flag, which is then prefixed with `backup_${TIMESTAMP_IN_SECONDS}_`. `TIMESTAMP_IN_SECONDS` is the date of generation, which is also used to determine expired snapshots that should be deleted.

**Note:** Replications are created asynchronously. The script will exit very fast while the replication could take a while.

### Replaying a snapshot

To replay a snapshot, swap `--sourceDbUrl` and `--targetDbUrl` and call the script with the `replay` command. That command takes only 1 parameter: `--dbPrefix` to determine which backup to play back. Matching databases will be replicated back to the target database with the `backup_${TIMESTAMP_IN_SECONDS}_` removed, so they'd look just like the original database.
