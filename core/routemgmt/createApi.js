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
 * Create an API Gateway to action mapping document in database:
 * https://docs.cloudant.com/document.html#documentCreate
 *
 * Parameters (all as fields in the message JSON object)
 *   host       Required. The database dns host name
 *   port       Required. The database port number
 *   protocol   Required. The database protocol (i.e. http, https)
 *   dbname     Required. The name of the database
 *   username   Required. The database user name used to access the database
 *   password   Required. The database user password
 *   namespace  Required. Openwhisk namespace of this request's originator
 *   basepath   Required if apidoc not provided.  API base path
 *   relpath    Required if apidoc not provided.  API path (relative to basepath)
 *   operation  Required if apidoc not provided.  API path's operation (i.e. GET, POST, etc)
 *   action     Required if apidoc not provided.  Object with the following fields
 *     backendMethod  Required if action provided. Normally set to POST
 *     backendUrl     Required if action provided. Complete invocable URL of the action
 *     name           Required if action provided. Entity name of action (incl package if specified)
 *     namespace      Required if action provided. Namespace in which the action resides
  *    authkey        Required if action provided. Authorization needed to call the action
 *   apiname    Required if apidoc not provided.  API friendly title
 *   swagger    Required if basepath is not provided.  Entire swagger document specifying API
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
    return Promise.reject(badArgMsg);
  }
  var dbname = message.dbname;

  // message.apidoc already validated; creating shortcut to it
  var doc;
  if (typeof message.apidoc === 'object') {
    doc = message.apidoc;
  } else if (typeof message.apidoc === 'string') {
    doc = JSON.parse(message.apidoc);
  }
  if (doc) {
    doc.documentTimestamp = (new Date()).toString();
  }

  var dbDocId = message.namespace+":"+getBasePath(message);

  // Log parameter values
  console.log('DB host             : '+message.host);
  console.log('DB port             : '+message.port);
  console.log('DB protocol         : '+message.protocol);
  console.log('DB username         : '+message.username);
  console.log('DB database         : '+message.dbname);
  console.log('DB database         : '+message.dbname);
  console.log('docid               : '+dbDocId);
  console.log('apiname             : '+message.apiname);
  console.log('namespace           : '+message.namespace);
  console.log('basepath            : '+getBasePath(message));
  console.log('relpath             : '+message.relpath);
  console.log('operation           : '+message.operation);
  console.log('action name         : '+message.action.name);
  console.log('action namespace    : '+message.action.namespace);
  console.log('action backendMethod: '+message.action.backendMethod);
  console.log('action backendUrl   : '+message.action.backendUrl);
  console.log('swagger             :\n'+JSON.stringify(doc , null, 2));


  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return Promise.reject('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(dbname);

  // Creating/augmenting an API
  // 1. Retrieve any existing API matching namespace+basepath
  // 2. If no document, create an initial API document
  // 3. If relpath & operation are in API doc, return error (need to update, not create)
  // 4. Add new relpath & operation to API doc
  // 5. Save doc in DB
  var newDoc = false;
  return getDbApiDoc(message.namespace, message.basepath)
  .then(function(dbdoc) {
    // Got document
    console.log('Got API document from DB: ', JSON.stringify(dbdoc));
    return dbdoc;
  }, function(err) {
    console.error('Got DB error: ', err);
    if (err.error == "not_found" && err.reason == "missing" && err.headers.statusCode == 404) {
      // No document.  Create an initial one
      console.log('API document not found; creating a new one:', err);
      newDoc = true;
      return makeTemplateDbApiDoc(message);
    }
    return Promise.reject(err);
  })
  .then(function(dbApiDoc) {
    console.log('Got API document from DB: ', JSON.stringify(dbApiDoc));
    // Check if relpath and operation are already in the API document
    if (dbApiDoc.apidoc.paths[message.relpath] && dbApiDoc.apidoc.paths[message.relpath][message.operation]) {
      console.error('Operation '+message.operation+' already exists under path '+message.relpath+ ' under basepath '+message.basepath);
      return Promise.reject('Operation '+message.operation+' already exists under path '+message.relpath+ ' under basepath '+message.basepath);
    } else {
        // Add new relpath & operation to API doc
        console.log('Adding new relpath:operation ('+message.relpath+':'+message.operation+') to document '+dbDocId);
        dbApiDoc = addPathToDbApiDoc(dbApiDoc, message);
    }
    return(dbApiDoc)
  })
  .then(function(dbApiDoc) {
    // API doc is updated and ready to write out to the DB
    console.log('API document ready to write to DB: ', JSON.stringify(dbApiDoc));
    return updateApiDocInDb(cloudantDb, dbApiDoc, dbDocId);
  })
  .catch(function(reason) {
    console.error('API configuration failure: '+JSON.stringify(reason));
    return Promise.reject('API configuration failure: '+JSON.stringify(reason));
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
  //return new Promise( function (resolve, reject) {
    //whisk.invoke({
    return whisk.invoke({
      name: actionName,
      blocking: true,
      parameters: params
    })
    .then(function (activation) {
      console.log('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') ok');
      console.log('Results: '+JSON.stringify(activation));
      if (activation && activation.result && activation.result._rev) {
        //resolve(activation.result);
        return Promise.resolve(activation.result);
      } else {
        console.error('_rev value not returned!');
        //reject('Document for basepath \"'+basepath+'\" was not located');
        return Promise.reject('Document for namepace \"'+namespace+'\" and basepath \"'+basepath+'\" was not located');
      }
    })
    .catch(function (error) {
      console.error('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') error: '+JSON.stringify(error));
      //reject(error);
      return Promise.reject(error);
    });
  //});
}

/**
 * Create document in database.
 */
function updateApiDocInDb(cloudantDb, doc, docid) {
  return new Promise( function(resolve, reject) {
    cloudantDb.insert(doc, docid, function(error, response) {
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

  if(message.swagger && message.basepath) {
    return 'swagger and basepath are mutually exclusive and cannot be specified together.';
  }

  if ( !message.swagger && !(message.basepath && message.relpath && message.operation && message.apiname && message.action) )
  {
    return 'When swagger is not specified, basepath, relpath, operation, action, and apiname are required.';
  } else if ( !(message.action.backendMethod && message.action.backendUrl && message.action.name && message.action.namespace) ) {
    return 'An action must include backendMethod, backendUrl, name (of action) and namespace (of action).';
  }
  message.operation = message.operation.toLowerCase();

  if (message.swagger) {
    if (typeof message.swagger !== 'object') {
      return 'swagger field is ' + (typeof apidoc) + ' and should be an object or a JSON string.';
    } else if (typeof message.swagger === 'string') {
      try {
        var tmpdoc = JSON.parse(message.swagger);
      } catch (e) {
        return 'swagger field cannot be parsed. Ensure it is valid JSON.';
      }
    } else {
      tmpdoc = message.swagger;
    }
  }

  return '';
}

function getBasePath(message) {
  if (message.swagger) {
    return message.swagger.basePath;
  }
  return message.basepath;
}

// Create an API document to store in the DB.
// Assumes all message parameters have been validated already
function makeTemplateDbApiDoc(message) {
  var dbApiDoc = {};
  dbApiDoc.namespace = message.namespace;
  dbApiDoc.gwApiActivated = false;
  dbApiDoc.apidoc = {
    swagger: "2.0",
    info: {
      title: message.apiname,
      version: "1.0.0"
    },
    basePath: getBasePath(message),
    paths: {}
  };
  return dbApiDoc;
}

// Update an existing DB API document with new path configuration for a single path
function addPathToDbApiDoc(dbApiDoc, message) {
  var operation = message.operation;
  dbApiDoc.apidoc.paths[message.relpath] = {};
  dbApiDoc.apidoc.paths[message.relpath][operation] = {
    'x-ibm-op-ext': {
      backendMethod: message.action.backendMethod,
      backendUrl: message.action.backendUrl,
      policies: [
        {
          type: 'reqMapping',
          value: [
            {
              action: 'transform',
              from: {
                name: '*',
                location: 'query'
              },
              to: {
                name: '*',
                location: 'body'
              }
            },
            {
              action: 'insert',
              from: {
                value: 'Base '+message.action.authkey
              },
              to: {
                name: 'Authorization',
                location: 'header'
              }
            }
          ]
        }
      ]
    },
    responses: {
      default: {
        description: "Default response"
      }
    }
  };

  return dbApiDoc;
}