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
 * Retrieve all API Gateway to action mapping documents that are associated
 * with the specified API connection "group" as grouped by either path or name.
 *
 * Parameters (all as fields in the message JSON object)
 *   host       Required. The database dns host name
 *   port       Required. The database port number
 *   protocol   Required. The database protocol (i.e. http, https)
 *   dbname     Required. The name of the database
 *   username   Required. The database user name used to access the database
 *   password   Required. The database user password
 *   namespace  Required. The namespace under which the target API Gateway mappings are associated
 *   collectionname  Required if collectionpath is not specified. The target collection name
 *   collectionpath  Required if collectionname is not specified. The target collection path
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

  // Log parameter values
  console.log('DB host        : '+message.host);
  console.log('DB port        : '+message.port);
  console.log('DB protocol    : '+message.protocol);
  console.log('DB username    : '+message.username);
  console.log('DB database    : '+message.dbname);
  console.log('namespace      : '+message.namespace);
  console.log('collection name: '+message.collectionname);
  console.log('collection path: '+message.collectionpath);

  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return whisk.error('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(message.dbname);

  // Create the database 'key' query parameter used to filter the collection query/view results
  var viewName;
  var viewCollectionKey;
  if (message.collectionname) {
    viewName = 'routes-by-collection';
    viewCollectionKey = message.collectionname;
  }
  else {
    viewName = 'routes-by-path';
    viewCollectionKey = message.collectionpath;
  }
  var params = {key: [message.namespace, viewCollectionKey]}
  console.log('Calling DB view '+viewName+' with ', params);

  return queryView(cloudantDb, 'gwapis', viewName, params);
}

/**
 * Get view by design doc id and view name.
 */
function queryView(cloudantDb, designDocId, designDocViewName, params) {
  return new Promise(function (resolve, reject) {
    cloudantDb.view(designDocId, designDocViewName, params, function(error, response) {
      if (!error) {
        console.log('success', response);
        resolve(response);
      } else {
        console.error('error', JSON.stringify(error));
        reject(JSON.stringify(error));  // FIXME MWD issue with rejecting object; so using string
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
  if(!message) {
    console.error('No message argument!');
    return whisk.error('Internal error.  A message parameter was not supplied.');
  }
  if(!message.dbname) {
    return whisk.error('dbname is required.');
  }
  if(!message.collectionname && !message.collectionpath) {
    return whisk.error('collectionname or collectionpath is required.');
  }
  if(message.collectionname && message.collectionpath) {
    return whisk.error('Specify either collectionname or collectionpath, but not both.');
  }
  if(!message.namespace) {
    return whisk.error('namespace is required.');
  }

  return '';
}