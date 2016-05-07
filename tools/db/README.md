### Configure datastore

Before you can build and deploy OpenWhisk, you must configure a backing datastore. The system supports any self-managed [CouchDB](using-couchdb) instance or
[Cloudant](using-cloudant) as a cloud-based database service.

#### Using CouchDB

If you are using your own installation of CouchDB, make a note of the host, port, username and password. Then within your `openwhisk` directory, copy the file `template-couchdb-local.env` to `couchdb-local.env` and edit as appropriate. Note that:

   * the username must have administrative rights
   * the CouchDB instance must be accessible over `http` or `https` (the latter requires a valid certificate)
   * the CouchDB instance must set `reduce_limit` on views to `false` (see [this](couchdb/createAdmin.sh#L55) for how to do this via REST)
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

As an alternative to a self-managed CouchDB, you may want to try [Cloudant](https://cloudant.com) which is a cloud-based database service. 
There are two ways to get a Cloudant account and configure OpenWhisk to use it. 
You only need to establish an account once, either through IBM Bluemix or with Cloudant directly. 

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
Once you have created a Cloudant account, make note of the account `username` and `password` from the Cloudant dashboard, so you can create the required `cloudant-local.env`.

##### Setting the Cloudant credentials 
 
Within your `openwhisk` directory, copy the file `template-cloudant-local.env` to `cloudant-local.env` and edit as appropriate.

```
# Work out of your openwhisk directory
cd $HOME/openwhisk 

# Make a copy of the template
cp template-cloudant-local.env cloudant-local.env

# Set the credentials for username and password
sed -i.bak s/OPEN_WHISK_DB_USERNAME=/OPEN_WHISK_DB_USERNAME=username/g cloudant-local.env
sed -i.bak s/OPEN_WHISK_DB_PASSWORD=/OPEN_WHISK_DB_PASSWORD=password/g cloudant-local.env
```


#### Initializing database for authorization keys

The system requires certain authorization keys to install standard assets (i.e., samples) and provide guest access for running unit tests.
These are called immortal keys. If you are using a persisted datastore (e.g., Cloudant), you only need to perform this operation **once**.
If you are [using an ephemeral CouchDB container](#using-an-ephemeral-couchdb-container), you need to run this script every time you tear down and deploy the system.

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
  curl -s --user ... -X DELETE https://<cloudant-username>.cloudant.com/subjects
  {"error":"not_found","reason":"Database does not exist."}
  curl -s --user ... -X PUT https://<cloudant-username>.cloudant.com/subjects
  {"ok":true}
  {"ok":true,"id":"_design/subjects","rev":"1-..."}
  Create immortal key for guest ...
  {"ok":true,"id":"guest","rev":"1-..."}
  Create immortal key for whisk.system ...
  {"ok":true,"id":"whisk.system","rev":"1-..."}
  ```

