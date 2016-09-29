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
 * Delete all API Gateway to action mapping documents that are associated
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
  try{
    if(!message) {
      console.error('No message argument!');
      return whisk.error('Internal error.  A message parameter was not supplied.');
    }

    var cloudantOrError = getCloudantAccount(message);
    if (typeof cloudantOrError !== 'object') {
      console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
      return whisk.error('Internal error.  An unexpected object type was obtained.');
    }
    var cloudant = cloudantOrError;

    // Validate the remaining parameters (dbname, collectionname and namespace)
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

    // Log parameter values
    console.log('DB host        : '+message.host);
    console.log('DB port        : '+message.port);
    console.log('DB protocol    : '+message.protocol);
    console.log('DB username    : '+message.username);
    console.log('DB database    : '+message.dbname);
    console.log('namespace      : '+message.namespace);
    console.log('collection name: '+message.collectionname);
    console.log('collection path: '+message.collectionpath);

    var cloudantDb = cloudant.use(message.dbname);

    // Retrieve list of API configuration documents associated with the specified collection
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
    queryView(cloudantDb, 'gwapis', viewName, params, function(err, data) {
      if (err) {
        console.error('queryView() failed: '+JSON.stringify(err, null, 2));
        return whisk.error('Internal error.  Database query failed');
      } else {
        // Loop through list, deleting each API configuration
        var deleteFailed = false;
        if (data && data.rows) {
          if (data.rows instanceof Array) {
            var numApisToDelete = data.rows.length;
            var ApisProcessed = 0;
            console.log('Deleting '+numApisToDelete+' routes');
            if (numApisToDelete > 0) {
              for (var i = 0; i < numApisToDelete; i++) {
                deleteSingleRoute(cloudantDb, data.rows[i].id, function(err) {
                  if (err) {
                    console.error('Error deleting API route '+data.rows[ApisProcessed].id);
                    deleteFailed = true;
                  } else {
                    console.log('Deleted API route '+data.rows[ApisProcessed].id);
                  }
                  ApisProcessed++;
                  console.log('ApisProcessed: '+ApisProcessed+', numApisToDelete: '+numApisToDelete);
                  if (ApisProcessed == numApisToDelete ) {
                    console.log('Completed processing all collection routes');
                    whisk.done();
                  }
                });
              }
            } else {
              return whisk.error('Collection '+viewCollectionKey+" has no routes to delete.");
            }
          } else {
            return whisk.error('Internal error.  Unexpected data.rows type');
          }
        } else {
          return whisk.error('Collection does not have any routes');
        }
      }
    });

    return whisk.async();
  }
  catch(e) {
    console.error('Internal error: '+e);
    return whisk.error('Internal error.  '+e);
  }
}

/**
 * Get view by design doc id and view name.
 */
function queryView(cloudantDb, designDocId, designDocViewName, params, callback) {
  cloudantDb.view(designDocId, designDocViewName, params, function(error, response) {
    if (!error) {
      console.log('success', response);
      callback(null, response);
    } else {
      console.error('error', error);
      callback(error, null);
    }
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

function deleteSingleRoute(db, docid, callback) {
  var actionName = '/whisk.system/routemgmt/deleteRoute';
  var params = { 'docid': docid };
  whisk.invoke({
    name: actionName,
    blocking: true,
    parameters: params,
    next: function(error, activation) {
      if (!error) {
        console.log('whisk.invoke('+actionName+', '+docid+') ok');
        console.log('Results: '+JSON.stringify(activation, null, 2));
        callback(null);
      } else {
        console.error('whisk.invoke('+actionName+','+docid+') error:\n'+JSON.stringify(error, null, 2));
        callback(error);
      }
    }
  });
}
