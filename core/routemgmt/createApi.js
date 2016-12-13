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
 *   basepath   Required if swagger not provided.  API base path or existing API name
 *   relpath    Required if swagger not provided.  API path (relative to basepath)
 *   operation  Required if swagger not provided.  API path's operation (i.e. GET, POST, etc)
 *   action     Required if swagger not provided.  Object with the following fields
 *     backendMethod  Required if action provided. Normally set to POST
 *     backendUrl     Required if action provided. Complete invocable URL of the action
 *     name           Required if action provided. Entity name of action (incl package if specified)
 *     namespace      Required if action provided. Namespace in which the action resides
 *     authkey        Required if action provided. Authorization needed to call the action
 *   apiname    Optional if swagger not provided.  API friendly title
 *   swagger    Required if basepath is not provided.  Entire swagger document specifying API
 *   stagedId   Optional. Used to create a "staged" DB document that will be committed later.
 *              Staged DB documents use "stagedId" as a document id prefix.  The committed
 *              DB doc's id does not have this prefix.
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

  // message.swagger already validated; creating shortcut to it
  var swaggerObj;
  if (typeof message.swagger === 'object') {
    swaggerObj = message.swagger;
  } else if (typeof message.swagger === 'string') {
    swaggerObj = JSON.parse(message.swagger);
  }
  message.swaggerOriginal = message.swagger;
  message.swagger = swaggerObj;

  var dbDocId = "API:"+message.namespace+":"+getBasePath(message);
  var dbStagedDocId;

  // Log parameter values
  console.log('DB host             : '+confidentialPrint(message.host));
  console.log('DB port             : '+message.port);
  console.log('DB protocol         : '+message.protocol);
  console.log('DB username         : '+confidentialPrint(message.username));
  console.log('DB password         : '+confidentialPrint(message.password));
  console.log('DB database         : '+message.dbname);
  console.log('docid               : '+dbDocId);
  console.log('stagedId            : '+message.stagedId);
  console.log('apiname             : '+message.apiname);
  console.log('namespace           : '+message.namespace);
  console.log('basepath/name       : '+getBasePath(message));
  console.log('relpath             : '+message.relpath);
  console.log('operation           : '+message.operation);
  if (message.action) {
    console.log('action name         : '+message.action.name);
    console.log('action namespace    : '+message.action.namespace);
    console.log('action backendMethod: '+message.action.backendMethod);
    console.log('action backendUrl   : '+message.action.backendUrl);
  }
  console.log('swagger             :\n'+JSON.stringify(swaggerObj , null, 2));

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
  return getDbApiDoc(message.namespace, getBasePath(message))
  .then(function(dbdoc) {
    // Got document
    console.log('Got API document from DB: ', JSON.stringify(dbdoc));

    // If an entire swagger document is used to configure the API, then having
    // an existing configuration for the same API is unexpected
    if (message.swagger) {
      console.error('An API configuration already exists for basepath '+getBasePath(message));
      return Promise.reject('An API configuration already exists for basepath '+getBasePath(message));
    }

    // If the provided API name does not match the existing API document's name, return an error
    if (message.apiname && (message.apiname != dbdoc.apidoc.info.title)) {
      return Promise.reject('The existing API already has an API name of \''+dbdoc.apidoc.info.title+'\'');
    }
    dbDocId = dbdoc._id;
    return dbdoc;
  }, function(err) {
    console.error('Got API DB error: ', err);
    if ( (err.error.error == "not_found" && err.error.statusCode == 404)) {  // err.error.reason == "missing" or "deleted"
      // No document.  Create an initial one - but ONLY if a /basepath was provided
      if (message.basepath.indexOf('/') == 0) {
        console.log('API document not found; creating a new one');
        newDoc = true;
        return makeTemplateDbApiDoc(message);
      }
      console.log('API document not found. Since an API name was provided and not a basepath; a new API cannot be created');
    }
    console.error('DB request failed');
    return Promise.reject(err);
  })
  .then(function(dbApiDoc) {
    console.log('Got DB API document: ', JSON.stringify(dbApiDoc));

    // If the API is to be saved in a staged doc, take the current API doc and convert it to a "staged" doc
    //   If the API's staged doc exists in the DB
    //     - Retrieve the existing staged doc's _id and _rev values
    //     - Replace the current API doc's _id and _rev values with the staged doc values (_rev needed to update existing doc)
    //   If the staged doc does not exist
    //     - Replace the current API doc's _id value with the associated staged doc id (_rev not needed)
    if (message.stagedId) {
      dbStagedDocId = message.stagedId+dbDocId;
      return getDbApiDoc(message.namespace, getBasePath(message), dbStagedDocId)
      .then(function(stageddbApiDoc) {
        console.log('Got staged DB API document: ', JSON.stringify(stageddbApiDoc));
        // If the committed API document exists, use it's contents instead of the staged document contents.
        // BUT, be sure use the staged _id and _rev
        if (dbApiDoc._rev) {
          console.log('Converting DB API document to staged');
          dbApiDoc._id = stageddbApiDoc._id;
          dbApiDoc._rev = stageddbApiDoc._rev;
          return(dbApiDoc);
        }
        console.log('No existing DB API document. Existing staged DB API document. Use existing staged _id and _rev on a replacement doc');
        var tempStagedDbApiDoc = makeTemplateDbApiDoc(message);
        tempStagedDbApiDoc._id = stageddbApiDoc._id;
        tempStagedDbApiDoc._rev = stageddbApiDoc._rev;
        return(tempStagedDbApiDoc);
      }, function(err) {
        console.error('Got DB error while retrieving staged DB API document: ', err);
        if ( (err.error.error == "not_found" && err.error.statusCode == 404)) {  // err.error.reason == "missing" or "deleted"
          // No staged API document.
          console.log('Staged API document not found; converting committed DB API doc to staged');
          if (dbApiDoc._rev) {
            delete dbApiDoc._rev;
            dbApiDoc._id = dbStagedDocId;
          }
          dbDocId = dbStagedDocId;
          return (dbApiDoc);
        }
        console.error('Internal error.  DB failure occured while looking for staging API document.');
        return Promise.reject(err);
      })
    }
    return(dbApiDoc);
  })
  .then(function(dbApiDoc) {
    // If a swagger file is not provided, then Check if relpath and operation are already in the API document
    if (!message.swagger) {
        if (dbApiDoc.apidoc.paths[message.relpath] && dbApiDoc.apidoc.paths[message.relpath][message.operation]) {
          console.error('Operation '+message.operation+' already exists under path '+message.relpath+ ' under basepath '+dbApiDoc.apidoc.basePath);
          return Promise.reject('Operation '+message.operation+' already exists under path '+message.relpath+ ' under basepath '+dbApiDoc.apidoc.basePath);
        } else {
            // Add new relpath & operation to API doc
            console.log('Adding new relpath:operation ('+message.relpath+':'+message.operation+') to document '+dbDocId);
            dbApiDoc = addPathToDbApiDoc(dbApiDoc, message);
        }
    }
    return(dbApiDoc)
  })
  .then(function(dbApiDoc) {
    // API doc is updated and ready to write out to the DB
    console.log('API document ready to write to DB: ', JSON.stringify(dbApiDoc));

    // If the document already exists (i.e. has _rev), update it; otherwise create
    if (dbApiDoc._rev)
      return updateDbApiDoc(cloudantDb, dbApiDoc);
    else
      return createDbApiDoc(cloudantDb, dbApiDoc, dbDocId);
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
function getDbApiDoc(namespace, basepath, docid) {
  var actionName = '/whisk.system/routemgmt/getApi';
  var params = {
    'namespace': namespace,
    'basepath': basepath
  }
  if (docid) params.docid = docid;
  return whisk.invoke({
    name: actionName,
    blocking: true,
    parameters: params
  })
  .then(function (activation) {
    console.log('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') ok');
    console.log('Results: '+JSON.stringify(activation));
    if (activation && activation.result && activation.result.apis &&
        activation.result.apis.length == 1 && activation.result.apis[0].value &&
        activation.result.apis[0].value._rev) {
      return Promise.resolve(activation.result.apis[0].value);
    } else if (activation && activation.result && activation.result.apis &&
               activation.result.apis.length > 1) {
          console.error('Multiple API docs returned!');  // Only expected case is when >1 basepaths have the same API Name
          return Promise.reject({
            error: {
              statusCode: 409,
              error: 'conflict',
              msg: 'Multiple APIs have the API Name \"'+basepath+'\"; specify the basepath of the API you want to use.'
            }
          });
    } else {
      // No API document.  Simulate the DB 'not found' error.
      console.error('Invalid API doc returned!');
      return Promise.reject({
        error: {
          statusCode: 404,
          error: 'not_found',
          msg: 'Document for namepace \"'+namespace+'\" and basepath \"'+basepath+'\" was not located'
        }
      });
    }
  })
  .catch(function (error) {
    console.error('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') error: '+JSON.stringify(error));
    return Promise.reject(error);
  });
}

/**
 * Create document in database.
 */
function createDbApiDoc(cloudantDb, doc, docid) {
  return new Promise( function(resolve, reject) {
    cloudantDb.insert(doc, docid, function(error, response) {
      if (!error) {
        console.log("createDbApiDoc: success", response);
        doc._id = response.id;
        doc._rev = response.rev;
        resolve(doc);
      } else {
        console.log("createDbApiDoc: error", JSON.stringify(error))
        reject(error);
      }
    });
  });
}

/**
 * Update the existing document in database.
 */
function updateDbApiDoc(cloudantDb, doc) {
  return new Promise( function(resolve, reject) {
    cloudantDb.insert(doc, function(error, response) {
      if (!error) {
        console.log("updateDbApiDoc: success", response);
        doc._id = response.id;
        doc._rev = response.rev;
        resolve(doc);
      } else {
        console.log("updateDbApiDoc: error", JSON.stringify(error))
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

 var tmpSwaggerDoc
  if(message.swagger) {
    if (message.basepath) {
      return 'swagger and basepath are mutually exclusive and cannot be specified together.';
    }
    if (typeof message.swagger == 'object') {
      tmpSwaggerDoc = message.swagger;
    } else if (typeof message.swagger === 'string') {
      try {
        tmpSwaggerDoc = JSON.parse(message.swagger);
      } catch (e) {
        return 'swagger field cannot be parsed. Ensure it is valid JSON.';
      }
    } else {
      return 'swagger field is ' + (typeof message.swagger) + ' and should be an object or a JSON string.';
    }
    console.log('Swagger JSON object: ', tmpSwaggerDoc);
    if (!tmpSwaggerDoc.basePath) {
      return 'swagger is missing the basePath field.';
    }
    if (!tmpSwaggerDoc.paths) {
      return 'swagger is missing the paths field.';
    }
    if (!tmpSwaggerDoc.info) {
      return 'swagger is missing the info field.';
    }
  } else {
    if (!message.basepath) {
      return 'basepath is required when swagger is not specified.';
    }

    if (!message.relpath) {
      return 'relpath is required when swagger is not specified.';
    }

    if (!message.operation) {
      return 'operation is required when swagger is not specified.';
    }

    if (!message.action) {
      return 'action is required when swagger is not specified.';
    }

    if (!message.action.backendMethod) {
      return 'action is missing the backendMethod field.';
    }

    if (!message.action.backendUrl) {
      return 'action is missing the backendUrl field.';
    }

    if (!message.action.namespace) {
      return 'action is missing the namespace field.';
    }

    if(!message.action.name) {
      return 'action is missing the name field.';
    }

    if (!message.action.authkey) {
      return 'action is missing the authkey field.';
    }

    message.operation = message.operation.toLowerCase();
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
  if (message.swagger) {
    dbApiDoc.apidoc = message.swagger;
  } else {
    dbApiDoc.apidoc = {
      swagger: "2.0",
      info: {
        title: message.apiname || message.basepath,
        version: "1.0.0"
      },
      basePath: getBasePath(message),
      paths: {}
    };
  }

  return dbApiDoc;
}

// Update an existing DB API document with new path configuration for a single path
function addPathToDbApiDoc(dbApiDoc, message) {
  var operation = message.operation;
  var auth_base64 = Buffer.from(message.action.authkey,'ascii').toString('base64');

  // If the relative path already exists, append to it; otherwise create it
  if (!dbApiDoc.apidoc.paths[message.relpath]) {
    dbApiDoc.apidoc.paths[message.relpath] = {};
  }
  dbApiDoc.apidoc.paths[message.relpath][operation] = {
    'x-ibm-op-ext': {
      backendMethod: message.action.backendMethod,
      backendUrl: message.action.backendUrl,
      actionName: message.action.name,
      actionNamespace: message.action.namespace,
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
                value: 'Basic '+auth_base64
              },
              to: {
                name: 'Authorization',
                location: 'header'
              }
            },
            {
              action: 'insert',
              from: {
                value: 'application/json'
              },
              to: {
                name: 'Content-Type',
                location: 'header'
              }
            },
            {
              action: 'insert',
              from: {
                value: 'true'
              },
              to: {
                name: 'blocking',
                location: 'query'
              }
            },
            {
              action: 'insert',
              from: {
                value: 'true'
              },
              to: {
                name: 'result',
                location: 'query'
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

function confidentialPrint(str) {
    var printStr;
    if (str) {
        printStr = 'XXXXXXXXXX';
    }
    return printStr;
}
