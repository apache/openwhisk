/**
 *
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Delete an API Gateway to action mapping document from the database:
 * https://docs.cloudant.com/document.html#delete
 *
 * Parameters (all as fields in the message JSON object)
 *   host       Required. The database dns host name
 *   port       Required. The database port number
 *   protocol   Required. The database protocol (i.e. http, https)
 *   dbname     Required. The name of the database
 *   username   Required. The database user name used to access the database
 *   password   Required. The database user password
 *   namespace  Required. Namespace of API author
 *   basepath   Required. Base path of the API
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
 var request = require('request');

function main(message) {
  var badArgMsg = '';
  if (badArgMsg = validateArgs(message)) {
    return whisk.error(badArgMsg);
  }

  // Log parameter values
  console.log('DB host    : '+message.host);
  console.log('DB port    : '+message.port);
  console.log('DB protocol: '+message.protocol);
  console.log('DB username: '+message.username);
  console.log('DB database: '+message.dbname);
  console.log('namespace  : '+message.namespace);
  console.log('basepath   : '+message.basepath);

  // The host, port, protocol, username, and password parameters are validated here
  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return whisk.error('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(message.dbname);

  // Delete an API route
  // 1. Obtain current DB doc.  It will have the _rev and _id needed for deletion
  // 2. If the API is activated, do not delete it; return an error
  // 3. If the API is no activated, delete the document from the DB
  var routeDeleted = false;
  var dbUpdated = false;
  var docRev;
  var gwApiGuid;

  return getDbApiDoc(message.namespace, message.basepath)
  .then(function(dbdoc) {
      console.log('Found API doc in db: '+JSON.stringify(dbdoc));
      if (dbdoc.gwApiActivated) {
        return Promise.reject('API is active and cannot be deleted.  Once the API is deactivated, it can be deleted.')
      }
      return deleteApiFromDb(cloudantDb, dbdoc._id, dbdoc._rev);
  })
  .catch(function(reason) {
      // FIXME MWD Possibly need to rollback some operations ??
      console.error('API deletion failure: '+reason);
      return Promise.reject('API deletion failure: '+reason);
  });
}


function getCloudantAccount(message) {
  // full cloudant URL - Cloudant NPM package has issues creating valid URLs
  // when the username contains dashes (common in Bluemix scenarios)
  var cloudantUrl;

  if (message.url) {
    // use bluemix binding
    cloudantUrl = message.url;
  } else {
    if (!message.host) {
      whisk.error('cloudant account host is required.');
      return;
    }
    if (!message.username) {
      whisk.error('cloudant account username is required.');
      return;
    }
    if (!message.password) {
      whisk.error('cloudant account password is required.');
      return;
    }
    if (!message.port) {
      whisk.error('cloudant account port is required.');
      return;
    }
    if (!message.protocol) {
      whisk.error('cloudant account protocol is required.');
      return;
    }

    cloudantUrl = message.protocol + "://" + message.username + ":" + message.password + "@" + message.host + ":" + message.port;
  }

  return require('cloudant')({
    url: cloudantUrl
  });
}

// Retrieve the specific API document from the DB
// This is a wrapper around an action invocation (getApi)
function getDbApiDoc(namespace, basepath) {
  var actionName = '/whisk.system/routemgmt/getApi';
  var params = {
    'namespace': namespace,
    'basepath': basepath
  }
  console.log('getDbApiDoc() for namespace:basepath: '+namespace+':'+basepath);
  return whisk.invoke({
    name: actionName,
    blocking: true,
    parameters: params
  })
  .then(function (activation) {
    console.log('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') ok');
    console.log('Results: '+JSON.stringify(activation));
    if (activation && activation.result && activation.result._rev) {
      return Promise.resolve(activation.result);
    } else {
      console.error('_rev value not returned!');
      return Promise.reject('Document for namepace \"'+namespace+'\" and basepath \"'+basepath+'\" was not located');
    }
  })
  .catch(function (error) {
    console.error('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') error: '+JSON.stringify(error));
    return Promise.reject(error);
  });
}

/**
 * Delete document by id and rev.
 */
function deleteApiFromDb(cloudantDb, docId, docRev) {
  return new Promise( function(resolve, reject) {
    cloudantDb.destroy(docId, docRev, function(error, response) {
      if (!error) {
        console.log('success', response);
        resolve(response);
      } else {
        console.error('error', JSON.stringify(error));
        reject(error);
      }
    });
  });
}

function validateArgs(message) {
  var tmpdoc;
  if(!message) {
    console.error('No message argument!');
    return 'Internal error.  A message parameter was not supplied.';
  }

  if (!message.dbname) {
    return 'dbname is required.';
  }

  if (!message.namespace) {
    return 'namespace is required.';
  }

  if (!message.basepath) {
    return 'basepath is required.';
  }

  return '';
}
