/**
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
 * Retrieve an API Gateway to action mapping document from the database:
 * https://docs.cloudant.com/document.html#read
 * https://github.com/apache/couchdb-nano#dbgetdocname-params-callback
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
 *   relpath    Optional. Must be defined with 'operation'.  Filters API result to path/operation
 *   operation  Optional. Must be defined with 'relpath'.  Filters API result to path/operation
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/

function main(message) {
  var badArgMsg = '';
  if (badArgMsg = validateArgs(message)) {
    return whisk.error(badArgMsg);
  }
  var dbname = message.dbname;
  var docid = "API:"+message.namespace+":"+message.basepath

  // Log parameter values
  console.log('DB host    : '+message.host);
  console.log('DB port    : '+message.port);
  console.log('DB protocol: '+message.protocol);
  console.log('DB username: '+message.username);
  console.log('DB database: '+message.dbname);
  console.log('namespace  : '+message.namespace);
  console.log('basepath   : '+message.basepath);
  console.log('relpath    : '+message.relpath);
  console.log('operation  : '+message.operation);
  console.log('docid      : '+docid);

  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return whisk.error('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(message.dbname);

//  var viewName = 'routes-by-basepath';
//  var viewCollectionKey = message.basepath;
//  var params = {key: [message.namespace, viewCollectionKey]}
//  return readApiDocument(cloudantDb, 'gwapis', viewName, params);
  if (message.relpath && message.operation) {
    var params = {key: [message.namespace, message.basepath, message.relpath, message.operation.toLowerCase()]}
    return readFilteredApiDocument(cloudantDb, 'gwapis', 'route-by-ns-bp-rp-op', params);
  } else {
    return readApiDocument(cloudantDb, docid, {});
  }
}

function readApiDocument(cloudantDb, docId, params) {
  return new Promise (function(resolve,reject) {
    cloudantDb.get(docId, params, function(error, response) {
      if (!error) {
        console.log('success', JSON.stringify(response));
        resolve(response);
      } else {
        console.error("DB error: ",error.name, error.error, error.reason, error.headers.statusCode);
        reject(error);
        //FIXME MWD reject("some error string instead of reject(error)")
      }
    });
  });
}

function readFilteredApiDocument(cloudantDb, designDocId, designDocViewName, params) {
  return new Promise(function (resolve, reject) {
    cloudantDb.view(designDocId, designDocViewName, params, function(error, response) {
      if (!error) {
        console.log('success', response);
        resolve(response);
      } else {
        console.error('error', JSON.stringify(error));
        reject(error);  // FIXME MWD issue with rejecting object
      }
    });
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


function validateArgs(message) {
  var tmpdoc;
  if(!message) {
    console.error('No message argument!');
    return 'Internal error.  A message parameter was not supplied.';
  }

  if (!message.dbname) {
    return 'dbname is required.';
  }

  if(!message.namespace) {
    return 'namespace is required.';
  }

  if(!message.basepath) {
    return 'basepath is required.';
  }

  return '';
}
