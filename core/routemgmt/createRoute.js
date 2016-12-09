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
 *   gwUrl      Required. The API Gateway base path (i.e. http://gw.com)
 *   __ow_meta_namespace  Required. Namespace of API author; set by controller
 *                        Use this value to override namespace values in the apidoc
 *                        Don't override namespace values in the swagger though
 *   apidoc     Required. The API Gateway mapping document
 *      namespace          Required.  Namespace of user/caller
 *      apiName            Optional if swagger not specified.  API descriptive name
 *      gatewayBasePath    Required if swagger not specified.  API base path
 *      gatewayPath        Required if swagger not specified.  Specific API path (relative to base path)
 *      gatewayMethod      Required if swagger not specified.  API path operation
 *      id                 Optional if swagger not specified.  Unique id of API
 *      action             Required. if swagger not specified
 *           name            Required.  Action name (includes package)
 *           namespace       Required.  Action namespace
 *           backendMethod   Required.  Action invocation REST verb.  "POST"
 *           backendUrl      Required.  Action invocation REST url
 *           authkey         Required.  Action invocation auth key
 *      swagger            Required if gatewayBasePath not provided.  API swagger JSON
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
 var request = require('request');

function main(message) {
  console.log('createRoute:  params: '+JSON.stringify(message));
  var badArgMsg = '';
  if (badArgMsg = validateArgs(message)) {
    return whisk.error(badArgMsg);
  }
  var dbname = message.dbname;
  var gwInfo = {
    gwUrl: message.gwUrl,
    gwAuth: message.gwAuth
  };

  // Replace the CLI provided namespace valuse with the controller provided namespace value
  updateNamespace(message.apidoc, message.__ow_meta_namespace);

  // message.apidoc already validated; creating shortcut to it
  var doc;
  if (typeof message.apidoc === 'object') {
    doc = message.apidoc;
  } else if (typeof message.apidoc === 'string') {
    doc = JSON.parse(message.apidoc);
  }

  // message.swagger already validated; creating object
  var swaggerObj;
  if (typeof doc.swagger === 'object') {
    swaggerObj = doc.swagger;
  } else if (typeof doc.swagger === 'string') {
    swaggerObj = JSON.parse(doc.swagger);
  }
  doc.swaggerOriginal = doc.swagger;
  doc.swagger = swaggerObj;

  var basepath;
  if (doc.swagger) {
    basepath = doc.swagger.basePath;
  } else {
    basepath = doc.gatewayBasePath;
  }

  //doc.documentTimestamp = (new Date()).toString();
  //var docid = doc.namespace+":"+doc.gatewayMethod.toUpperCase()+":"+doc.action;

  // Log parameter values
  console.log('DB host    : '+message.host);
  console.log('DB port    : '+message.port);
  console.log('DB protocol: '+message.protocol);
  console.log('DB username: '+confidentialPrint(message.username));
  console.log('DB password: '+confidentialPrint(message.password));
  console.log('DB database: '+message.dbname);
  console.log('GW URL     : '+message.gwUrl);
  console.log('GW Auth API: '+message.gwAuth);
  console.log('namespace  : '+doc.namespace);
  console.log('API name   : '+doc.apiName);
  console.log('basepath   : '+doc.gatewayBasePath);
  console.log('relpath    : '+doc.gatewayPath);
  console.log('GW method  : '+doc.gatewayMethod);
  if (doc.action) {
    console.log('action name: '+doc.action.name);
    console.log('action namespace: '+doc.action.namespace);
    console.log('action backendUrl: '+doc.action.backendUrl);
    console.log('action backendMethod: '+doc.action.backendMethod);
  }
  console.log('apidoc     :\n'+JSON.stringify(doc , null, 2));

  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return Promise.reject('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(message.dbname);

  // Create and activate a new API path
  // 1. Create or update the API configuration in the DB
  // 2. Activate the entire API
  var stagedApiCreated = false;
  return createStagedApi(message.apidoc)
  .then(function(stagedApiDbDoc) {
    console.log('Staged API successfully added to DB')
    console.log('Staged API DB Doc: ', stagedApiDbDoc);
    stagedApiCreated = true;
    return activateApi(doc.namespace, getBasePath(doc), stagedApiDbDoc._id);
  })
  .then(function(stagedApiDbDoc) {
    console.log('API activated!');
    // The staged API configuration is now active on the API GW
    // So, "commit" the API document
    return commitStagedApi(cloudantDb, doc, stagedApiDbDoc)
  })
  .catch(function(reason) {
    console.error('API route creation failure: '+JSON.stringify(reason))
    return Promise.reject('API route creation failure: '+JSON.stringify(reason));
  });
}

/* Create/update a "staged" (i.e. not comitted until sent to API GW) the API in the DB
 * This is a wrapper around an action invocation (createApi)
 * Parameters
 *   namespace  Required. Openwhisk namespace of this request's originator
 *   basepath   Required if swagger not provided.  API base path
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
 */
function createStagedApi(apiConfig) {
  var actionName = '/whisk.system/routemgmt/createApi';
  var params;
  if (!apiConfig.swagger) {
    params = {
      'namespace': apiConfig.namespace,
      'basepath': apiConfig.gatewayBasePath,
      'relpath': apiConfig.gatewayPath,
      'operation': apiConfig.gatewayMethod,
      'apiname': apiConfig.apiName,
      'action': {
        'backendMethod': apiConfig.action.backendMethod,
        'backendUrl': apiConfig.action.backendUrl,
        'name': apiConfig.action.name,
        'namespace': apiConfig.action.namespace,
        'authkey': apiConfig.action.authkey
      }
    }
  } else {
      params = {
        'namespace': apiConfig.namespace,
        'swagger': apiConfig.swagger
      }
  }
  params.stagedId = 'STAGED_';  // Causes createApi to generate a staged API document in DB
  console.log('createStagedApi() params: ', params);
  return whisk.invoke({
      name: actionName,
      blocking: true,
      parameters: params
  })
  .then(function (activation) {
    console.log('whisk.invoke('+actionName+') ok');
    console.log('Results: '+JSON.stringify(activation));
    return Promise.resolve(activation.result);
  })
  .catch(function (error) {
    console.error('whisk.invoke('+actionName+') error:\n'+JSON.stringify(error));
    return Promise.reject(error);
  });
}

/* Create/update the API in the DB
 * This is a wrapper around an action invocation (createApi)
 * Parameters
 *   namespace  Required. Openwhisk namespace of this request's originator
 *   basepath   Required if apidoc not provided.  API base path
 *   docid      Optional. If specified, use it to identify the API DB document
 */
function activateApi(namespace, basepath, docid) {
  var actionName = '/whisk.system/routemgmt/activateApi';
  var params = {
    'namespace': namespace,
    'basepath': basepath
  }
  if (docid) params.docid = docid;
  console.log('activateApi() params: ', params);
  return whisk.invoke({
      name: actionName,
      blocking: true,
      parameters: params
  })
  .then(function (activation) {
    console.log('whisk.invoke('+actionName+') ok');
    console.log('Results: '+JSON.stringify(activation));
    return Promise.resolve(activation.result);
  })
  .catch(function (error) {
    console.error('whisk.invoke('+actionName+') error:\n'+JSON.stringify(error));
    return Promise.reject(error);
  });
}

/*
 * Copy the staged API DB document into a commited API DB document.
 */
function commitStagedApi(cloudantDb, apiConfig, stagedApiDbDoc) {
  var options = {};
  var srcDoc = stagedApiDbDoc._id;
  var destDoc = "API:"+apiConfig.namespace+":"+getBasePath(apiConfig);
  console.log('commitStagedApi() Copying staged API doc '+srcDoc+' to committed doc '+destDoc);
  return getApiDoc(apiConfig.namespace, getBasePath(apiConfig))
  .then(function(dbApiDoc) {
    if (dbApiDoc && dbApiDoc.apidoc) {
      console.log('commitStagedApi() destdoc '+destDoc+' exists; overwriting');
      options.overwrite = true;
    }
    return(destDoc);
  }, function(err) {
    if ((err.error.error == "not_found" && err.error.statusCode == 404)) {  // err.error.reason == "missing" or "deleted"
      console.log('No committed API document found');
    } else {
      console.log('DB error while retrieving committed API document');
      return Promise.reject(err);
    }
    return(destDoc);
  })
  .then(function(destDoc) {
    console.log('Copying staged API doc to commited doc');
    return copyApiDocument(cloudantDb, srcDoc, destDoc, options);
  })
  .then(function(copyResp) {
    console.log('Staged API doc is now committed');
    dbApiDoc = JSON.parse(JSON.stringify(stagedApiDbDoc));  // clone
    dbApiDoc._id = copyResp.id;
    dbApiDoc._rev = copyResp.rev;
    return Promise.resolve(dbApiDoc);
  })
  .catch(function(err) {
    console.log('commitStagedApi(): error: ',err);
    return Promise.reject(err);
  })
}

function getBasePath(apidoc) {
  if (apidoc.swagger) {
    return apidoc.swagger.basePath;
  }
  return apidoc.gatewayBasePath;
}

function copyApiDocument(cloudantDb, srcDoc, dstDoc, options) {
  console.log('copyApiDocument: srcDoc '+srcDoc+' dstDoc '+dstDoc+' copy options: ', options);
  return new Promise (function(resolve,reject) {
    cloudantDb.copy(srcDoc, dstDoc, options, function(error, response) {
      if (!error) {
        console.log('DB doc copy success', JSON.stringify(response));
        resolve(response);
      } else {
        console.error("DB doc copy error: ",error.name, error.error, error.reason, error.headers.statusCode);
        reject(error);
      }
    });
  });
}

// Retrieve the specific API document from the DB
// This is a wrapper around an action invocation (getApi)
function getApiDoc(namespace, basepath, docid) {
  var actionName = '/whisk.system/routemgmt/getApi';
  var params = {
    'namespace': namespace,
    'basepath': basepath
  }
  if (docid) params.docid = docid;
  console.log('getApiDoc() for: ', params);
    return whisk.invoke({
      name: actionName,
      blocking: true,
      parameters: params
    })
    .then(function (activation) {
      console.log('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') ok');
      console.log('Results: '+JSON.stringify(activation));
      if (activation && activation.result && activation.result.apis &&
        activation.result.apis.length > 0 && activation.result.apis[0].value &&
        activation.result.apis[0].value._rev) {
          return Promise.resolve(activation.result.apis[0].value);
      } else if (activation && activation.result && activation.result.apis &&
                 activation.result.apis.length == 0) {
          return Promise.resolve(null)
      } else {
        console.error('Invalid API doc returned!');
        return Promise.reject('Document for namepace \"'+namespace+'\" and basepath \"'+basepath+'\" was not located');
      }
    })
    .catch(function (error) {
      console.error('whisk.invoke('+actionName+', '+params.namespace+', '+params.basepath+') error: '+JSON.stringify(error));
      return Promise.reject(error);
    });
}

/*
 * Replace the namespace values that are used in the apidoc with the
 * specified namespace
 */
function updateNamespace(apidoc, namespace) {
  if (apidoc) {
    if (apidoc.action) {
      // The action namespace does not have to match the CLI user's namespace
      // If it is different, leave it alone; otherwise use the replacement namespace
      if (apidoc.namespace === apidoc.action.namespace) {
        apidoc.action.namespace = namespace;
        apidoc.action.backendUrl = replaceNamespaceInUrl(apidoc.action.backendUrl, namespace);      }
    }
    apidoc.namespace = namespace;
  }
}

/*
 * Take an OpenWhisk URL (i.e. action invocation URL) and replace the namespace
 * path parameter value with the provided namespace value
 */
function replaceNamespaceInUrl(url, namespace) {
  var namespacesPattern = /\/namespaces\/([\w@.-]+)\//;
  console.log('replaceNamespaceInUrl: url before - '+url);
  matchResult = url.match(namespacesPattern);
  if (matchResult != null) {
    console.log('replaceNamespaceInUrl: replacing namespace \''+matchResult[1]+'\' with \''+namespace+'\'');
    url = url.replace(namespacesPattern, '/namespaces/'+namespace+'/');
  }
  console.log('replaceNamespaceInUrl: url after - '+url);
  return url;
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
    return '__ow_meta_namespace is required.'
  }

  if(!message.apidoc) {
    return 'apidoc is required.';
  }
  if (typeof message.apidoc == 'object') {
    tmpdoc = message.apidoc;
  } else if (typeof message.apidoc === 'string') {
    try {
      tmpdoc = JSON.parse(message.apidoc);
    } catch (e) {
      return 'apidoc field cannot be parsed. Ensure it is valid JSON.';
    }
  } else {
    return 'apidoc field is of type ' + (typeof message.apidoc) + ' and should be a JSON object or a JSON string.';
  }

  if (!tmpdoc.namespace) {
    return 'apidoc is missing the namespace field';
  }

 var tmpSwaggerDoc
  if(tmpdoc.swagger) {
    if (tmpdoc.gatewayBasePath) {
      return 'swagger and gatewayBasePath are mutually exclusive and cannot be specified together.';
    }
    if (typeof tmpdoc.swagger == 'object') {
      tmpSwaggerDoc = tmpdoc.swagger;
    } else if (typeof tmpdoc.swagger === 'string') {
      try {
        tmpSwaggerDoc = JSON.parse(tmpdoc.swagger);
      } catch (e) {
        return 'swagger field cannot be parsed. Ensure it is valid JSON.';
      }
    } else {
      return 'swagger field is ' + (typeof tmpdoc.swagger) + ' and should be an object or a JSON string.';
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
    if (!tmpdoc.gatewayBasePath) {
      return 'apidoc is missing the gatewayBasePath field';
    }

    if (!tmpdoc.gatewayPath) {
      return 'apidoc is missing the gatewayPath field';
    }

    if (!tmpdoc.gatewayMethod) {
      return 'apidoc is missing the gatewayMethod field';
    }

    if (!tmpdoc.action) {
      return 'apidoc is missing the action field.';
    }

    if (!tmpdoc.action.backendMethod) {
      return 'action is missing the backendMethod field.';
    }

    if (!tmpdoc.action.backendUrl) {
      return 'action is missing the backendUrl field.';
    }

    if (!tmpdoc.action.namespace) {
      return 'action is missing the namespace field.';
    }

    if(!tmpdoc.action.name) {
      return 'action is missing the name field.';
    }

    if (!tmpdoc.action.authkey) {
      return 'action is missing the authkey field.';
    }
  }

  return '';
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

function confidentialPrint(str) {
    var printStr;
    if (str) {
        printStr = 'XXXXXXXXXX';
    }
    return printStr;
}
