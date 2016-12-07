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
 *   gwUrl      Required. The API Gateway base path (i.e. http://gw.com)
 *   __ow_meta_namespace  Required. Namespace of API author
 *   basepath   Required. Base path or API name of the API
 *   relpath    Optional. Delete just this relative path from the API.  Required if operation is specified
 *   operation  Optional. Delete just this relpath's operation from the API.
 *   force      Optional. Boolean. If true, the API will be automatically deactivated when deleting the entire API
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
 var request = require('request');

function main(message) {
  console.log("deleteApi: args: "+JSON.stringify(message));
  var badArgMsg = '';
  if (badArgMsg = validateArgs(message)) {
    return whisk.error(badArgMsg);
  }

  var gwInfo = {
    gwUrl: message.gwUrl,
  };
  if (message.gwUser && message.gwPwd) {
    gwInfo.gwAuth = Buffer.from(message.gwUser+':'+message.gwPwd,'ascii').toString('base64');
  }

  // Log parameter values
  console.log('DB host       : '+message.host);
  console.log('DB port       : '+message.port);
  console.log('DB protocol   : '+message.protocol);
  console.log('DB username   : '+confidentialPrint(message.username));
  console.log('DB password   : '+confidentialPrint(message.password));
  console.log('DB database   : '+message.dbname);
  console.log('GW URL        : '+message.gwUrl);
  console.log('GW User       : '+confidentialPrint(message.gwUser));
  console.log('GW Pwd        : '+confidentialPrint(message.gwPwd));
  console.log('__ow_meta_namespace : '+message.__ow_meta_namespace);
  console.log('basepath/name : '+message.basepath);
  console.log('relpath       : '+message.relpath);
  console.log('operation     : '+message.operation);
  console.log('force         : '+message.force);

  // If no relpath (or relpath/operation) is specified, delete the entire API
  var deleteEntireApi = !message.relpath;
  var force = message.force || false;

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
  // 2. If a relpath or relpath/operation is specified (delete subset of API)
  //    a. Remove that section from the DB doc
  //    b. Update DB with updated DB doc
  //    c. If API is activated, update the GW
  // 3. If relpath or replath/operation is NOT specified (delete entire API)
  //    a. If API is activated and force == false, return an error
  //    b. If API is activated and force == true
  //       i. Delete API from GW
  //       ii. Delete DB doc from DB
  // 2. If the API is activated, do not delete it; return an error
  // 3. If the API is not activated, delete the document from the DB
  var routeDeleted = false;
  var dbUpdated = false;
  var docRev;
  var gwApiGuid;
  var updatedDbDocOrErrMsg

  return getDbApiDoc(message.__ow_meta_namespace, message.basepath)
  .then(function(dbdoc) {
      console.log('Found API doc in db: '+JSON.stringify(dbdoc));
      if (!deleteEntireApi) {
          updatedDbDocOrErrMsg = removePath(dbdoc, message.relpath, message.operation);
          if (!updatedDbDocOrErrMsg._id) {
              console.error('Unable to remove specified relpath/operation');
              return Promise.reject(updatedDbDocOrErrMsg);  // On failure, updatedDbDocOrErrMsg is an error string
          }
          console.log('Updated API configuration: '+JSON.stringify(updatedDbDocOrErrMsg));
          return updateApiDocInDb(cloudantDb, updatedDbDocOrErrMsg)
          .then(function() {
              console.log('API configuration '+updatedDbDocOrErrMsg._id+' successfully updated in database');
              if (dbdoc.gwApiActivated) {
                  console.log('API configuration is active; updating gateway');
                  var gwdoc = makeGwApiDoc(updatedDbDocOrErrMsg);
                  // Since this is an update, add the API guid and Tenant guid
                  gwdoc.id = dbdoc.gwApiGuid;
                  gwdoc.tenantId = dbdoc.tenantId;
                  console.log('Updated API GW configuration: '+JSON.stringify(gwdoc));
                  return updateGatewayApi(gwInfo, updatedDbDocOrErrMsg.gwApiGuid, gwdoc);
              } else {
                  console.log('API configuration is not active; no need to update gateway');
                  return Promise.resolve();
              }
          }).then(function() {
              console.log('API configuration '+updatedDbDocOrErrMsg._id+' successfully updated');
              return Promise.resolve();
          })
      } else {
          if (dbdoc.gwApiActivated && !force) {
              return Promise.reject('API is active and cannot be deleted.  Once the API is deactivated, it can be deleted.')
          }
          return deleteGatewayApi(gwInfo, dbdoc.gwApiGuid)
          .then(function() {
              console.log('Gateway API '+dbdoc.gwApiGuid+' successfully removed from gateway');
              return deleteApiFromDb(cloudantDb, dbdoc._id, dbdoc._rev)
              .then(function() {
                  console.log('API configuration '+dbdoc._id+' successfully removed from the database');
                  return Promise.resolve();
              })
          })
      }
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
    console.log('whisk.invoke('+actionName+', '+params.__ow_meta_namespace+', '+params.basepath+') ok');
    console.log('Results: '+JSON.stringify(activation));
    if (activation && activation.result && activation.result.apis &&
        activation.result.apis.length == 1 && activation.result.apis[0].value &&
        activation.result.apis[0].value._rev) {
      return Promise.resolve(activation.result.apis[0].value);
    } else if (activation && activation.result && activation.result.apis &&
               activation.result.apis.length > 1) {
      console.error('Multiple API docs returned!');  // Only expected case is when API Name is used for >1 basepath
      return Promise.reject('Multiple APIs have the API Name \"'+basepath+'\"; specify the basepath of the API you want to delete');
    } else {
      console.error('Invalid API doc returned!');
      return Promise.reject('Document for namepace \"'+namespace+'\" and basepath \"'+basepath+'\" was not located');
    }
  })
  .catch(function (error) {
    console.error('whisk.invoke('+actionName+', '+params.__ow_meta_namespace+', '+params.basepath+') error: '+JSON.stringify(error));
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

/**
 * Update document in database.
 */
function updateApiDocInDb(cloudantDb, doc) {
  return new Promise( function(resolve, reject) {
    cloudantDb.insert(doc, function(error, response) {
      if (!error) {
        console.log("success", response);
        resolve(response);
      } else {
        console.log("error", JSON.stringify(error))
        reject(error);
      }
    });
  });
}

/**
 * Updates an existing API route on the API Gateway.
 *
 * @param gwInfo   Required.
 * @param    gwUrl   Required.  The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth  Required.  The credentials used to access the API Gateway REST endpoints
 * @param apiId    Required. Unique Gateway API Id
 * @param payload  Required. GW API configuration used to replace the existing configuration
 *
 * @return A promise for an object describing the result with fields error and response
 */
function updateGatewayApi(gwInfo, apiId, payload) {
  var options = {
    url: gwInfo.gwUrl+'/apis/'+apiId,
    agentOptions: {rejectUnauthorized: false},
    headers: {
      'Accept': 'application/json'
    },
    json: payload,  // Auto set header: 'Content-Type': 'application/json'
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }
  console.log('updateGatewayRoute: request: '+JSON.stringify(options, " ", 2));

  return new Promise(function(resolve, reject) {
    request.put(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('updateGatewayRoute: response status:'+ statusCode);
      error && console.error('Warning: updateGatewayRoute request failed: '+ JSON.stringify(error));
      body && console.log('updateGatewayRoute: response body: '+JSON.stringify(body));

      if (error) {
        console.error('updateGatewayRoute: Unable to update the API Gateway: '+JSON.stringify(error))
        reject('Unable to update the API Gateway: '+JSON.stringify(error));
      } else if (statusCode != 200) {
        console.error('updateGatewayRoute: Response code: '+statusCode)
        reject('Unable to update the API Gateway: Response failure code: '+statusCode);
      } else if (!body) {
        console.error('updateGatewayRoute: Unable to update the API Gateway: No response body')
        reject('Unable to update the API Gateway: No response received from the API Gateway');
      } else {
        resolve(body);
      }
    });
  });
}

/**
 * Removes an API route from the API Gateway.
 *
 * @param gwInfo Required.
 * @param    gwUrl   Required.  The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth  Required.  The credentials used to access the API Gateway REST endpoints
 * @param apiId  Required.  Unique Gateway API Id
 * @return A promise for an object describing the result with fields error and response
 */
function deleteGatewayApi(gwInfo, gwApiId) {
  var options = {
    url: gwInfo.gwUrl+'/apis/'+gwApiId,
    agentOptions: {rejectUnauthorized: false},
    headers: {
      'Accept': 'application/json'
    }
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }
  console.log('deleteGatewayApi: request: '+JSON.stringify(options, " ", 2));

  return new Promise(function(resolve, reject) {
    request.delete(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('deleteGatewayApi: response status:'+ statusCode);
      error && console.error('Warning: deleteGatewayApi request failed: '+ JSON.stringify(error));
      body && console.log('deleteGatewayApi: response body: '+JSON.stringify(body));

      if (error) {
        console.error('deleteGatewayApi: Unable to delete the API Gateway: '+JSON.stringify(error))
        reject('Unable to delete the API Gateway: '+JSON.stringify(error));
      } else if (statusCode != 200) {
        console.error('deleteGatewayApi: Response code: '+statusCode)
        reject('Unable to delete the API Gateway: Response failure code: '+statusCode);
      } else {
        resolve();
      }
    });
  });
}

/*
 * Create a JSON object that's compatible with the API GW REST interface.  Use the
 * API configuration JSON object stored in the DB as input
 */
function makeGwApiDoc(apiDbDoc) {
  var gwdoc = {};
  gwdoc.basePath = apiDbDoc.apidoc.basePath;
  gwdoc.name = apiDbDoc.apidoc.info.title;
  gwdoc.resources = {};
  for (var path in apiDbDoc.apidoc.paths) {
  console.log('Got dbapidoc path: ', JSON.stringify(path));
    gwdoc.resources[path] = {};
    var gwpathop = gwdoc.resources[path].operations = {};
    for (var operation in apiDbDoc.apidoc.paths[path]) {
      console.log('Got operation for path: ', operation);
      console.log('Got operation backendMethod: ', apiDbDoc.apidoc.paths[path][operation]['x-ibm-op-ext'].backendMethod);
      var gwop = gwpathop[operation] = {};
      gwop.backendMethod = apiDbDoc.apidoc.paths[path][operation]['x-ibm-op-ext'].backendMethod;
      gwop.backendUrl = apiDbDoc.apidoc.paths[path][operation]['x-ibm-op-ext'].backendUrl;
      gwop.policies = apiDbDoc.apidoc.paths[path][operation]['x-ibm-op-ext'].policies;
    }
  }
  return gwdoc;
}

/*
 * Update an existing DB API document by removing the specified relpath/operation section.
 * @param dbApiDoc   Required.  The JSON API document from the DB
 * @param relpath    Optional.  The relative path.  If not provided, the original dbApiDoc is returned
 * @param operation  Optional.  The operation under relpath.  If not provided, the entire relpath is deleted.
 *                              If relpath has no more operations, then the entire relpath is deleted.
 * @returns Updated JSON API document
 */
function removePath(dbApiDoc, relpath, operation) {
  console.log('removePath: relpath '+relpath+' operation '+operation);
  if (!relpath) {
      console.log('removePath: No relpath specified; nothing to remove');
      return 'No relpath provided; nothing to remove';
  }

  // If an operation is not specified, delete the entire relpath
  if (!operation) {
      console.log('removePath: No operation; removing entire relpath '+relpath);
      if (dbApiDoc.apidoc.paths[relpath]) {
          delete dbApiDoc.apidoc.paths[relpath];
      } else {
          console.log('removePath: relpath '+relpath+' does not exist in the DB doc; already deleted');
          return 'relpath '+relpath+' does not exist in the DB doc';
      }
  } else {
      if (dbApiDoc.apidoc.paths[relpath] && dbApiDoc.apidoc.paths[relpath][operation]) {
          delete dbApiDoc.apidoc.paths[relpath][operation];
          if (Object.keys(dbApiDoc.apidoc.paths[relpath]).length == 0) {
            console.log('removePath: after deleting operation '+operation+', relpath '+relpath+' has no more operations; so deleting entire relpath '+relpath);
            delete dbApiDoc.apidoc.paths[relpath];
          }
      } else {
          console.log('removePath: relpath '+relpath+' with operation '+operation+' does not exist in the DB doc');
          return 'relpath '+relpath+' with operation '+operation+' does not exist in the DB doc';
      }
  }

  return dbApiDoc;
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

  if (!message.gwUrl) {
    return 'gwUrl is required.';
  }

  if (!message.__ow_meta_namespace) {
    return '__ow_meta_namespace is required.';
  }

  if (!message.basepath) {
    return 'basepath is required.';
  }

  if (!message.relpath && message.operation) {
    return 'When specifying an operation, the relpath is required.';
  }

  if (message.force && (message.force != true) && (message.force != false) && (message.force != 'true') && (message.force != 'false')) {
    return 'Valid force values are true or false.';
  }

  if (message.operation) {
    message.operation = message.operation.toLowerCase();
  }

  return '';
}

function confidentialPrint(str) {
    var printStr;
    if (str) {
        printStr = 'XXXXXXXXXX';
    }
    return printStr;
}
