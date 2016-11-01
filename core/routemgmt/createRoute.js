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
 *   apidoc     Required. The API Gateway mapping document
 *      namespace          Required.  Namespace of user/caller
 *      apiName            Required if swagger not specified.  API descriptive name
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
  console.log('DB username: '+message.username);
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

  // Create and activate a new API path
  // 1. Create or update the API configuration in the DB
  // 2. Activate the entire API
  return configureApi(message.apidoc)
  .then(function(api) {
    console.log('API route configured successfully')
    return activateApi(doc.namespace, basepath)
  })
  .catch(function(reason) {
    console.error('API route creation failure: '+JSON.stringify(reason))
    return Promise.reject('API route creation failure: '+JSON.stringify(reason));
  });
}

/* Create/update the API in the DB
 * This is a wrapper around an action invocation (createApi)
 * Parameters
 *   namespace  Required. Openwhisk namespace of this request's originator
 *   basepath   Required if apidoc not provided.  API base path
 *   relpath    Required if apidoc not provided.  API path (relative to basepath)
 *   operation  Required if apidoc not provided.  API path's operation (i.e. GET, POST, etc)
 *   action     Required if apidoc not provided.  Object with the following fields
 *     backendMethod  Required if action provided. Normally set to POST
 *     backendUrl     Required if action provided. Complete invocable URL of the action
 *     name           Required if action provided. Entity name of action (incl package if specified)
 *     namespace      Required if action provided. Namespace in which the action resides
 *     authkey        Required if action provided. Authorization needed to call the action
 *   apiname    Required if apidoc not provided.  API friendly title
 *   swagger    Required if basepath is not provided.  Entire swagger document specifying API
 */
function configureApi(apiPath) {
  var actionName = '/whisk.system/routemgmt/createApi';
  var params;
  if (!apiPath.swagger) {
    params = {
      'namespace': apiPath.namespace,
      'basepath': apiPath.gatewayBasePath,
      'relpath': apiPath.gatewayPath,
      'operation': apiPath.gatewayMethod,
      'apiname': apiPath.apiName,
      'action': {
        'backendMethod': apiPath.action.backendMethod,
        'backendUrl': apiPath.action.backendUrl,
        'name': apiPath.action.name,
        'namespace': apiPath.action.namespace,
        'authkey': apiPath.action.authkey
      }
    }
  } else {
      params = {
        'namespace': apiPath.namespace,
        'swagger': apiPath.swagger
      }
  }
  console.log('configureApi() params: ', params);
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
 */
function activateApi(namespace, basepath) {
  var actionName = '/whisk.system/routemgmt/activateApi';
  var params = {
    'namespace': namespace,
    'basepath': basepath
  }
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

  if(!message.apidoc) {
    return 'apidoc is required.';
  }
  if (typeof message.apidoc !== 'object') {
    return 'apidoc field is ' + (typeof apidoc) + ' and should be an object or a JSON string.';
  } else if (typeof message.apidoc === 'string') {
    try {
      var tmpdoc = JSON.parse(message.apidoc);
    } catch (e) {
      return 'apidoc field cannot be parsed. Ensure it is valid JSON.';
    }
  } else {
    tmpdoc = message.apidoc;
  }

  if (!tmpdoc.namespace) {
    return 'apidoc is missing the namespace field';
  }

 var tmpSwaggerDoc
  if(tmpdoc.swagger) {
    if (tmpdoc.basepath) {
      return 'swagger and basepath are mutually exclusive and cannot be specified together.';
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

    if (!tmpdoc.apiName) {
      return 'apidoc is missing the apiName field';
    }

    if (!tmpdoc.action) {
      return 'apidoc is missing the action (action name) field.';
    }

    if (!tmpdoc.action.backendMethod) {
      return 'action is missing the backendMethod field.';
    }

    if (!tmpdoc.action.backendUrl) {
      return 'action is missing the backendMethod field.';
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