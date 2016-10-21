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
 *      apiName            Required.  API descriptive name
 *      gatewayBasePath    Required.  API base path
 *      gatewayPath        Required.  Specific API path (relative to base path)
 *      gatewayMethod      Required.  API path operation
 *      id                 Optional.  Unique id of API
 *      action             Required.
 *           name            Required.  Action name (includes package)
 *           namespace       Required.  Action namespace
 *           backendMethod   Required.  Action invocation REST verb.  "POST"
 *           backendUrl      Required.  Action invocation REST url
 *           authkey         Required.  Action invocation auth key
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
  console.log('action name: '+doc.action.name);
  console.log('action namespace: '+doc.action.namespace);
  console.log('action backendUrl: '+doc.action.backendUrl);
  console.log('action backendMethod: '+doc.action.backendMethod);
  console.log('apidoc     :\n'+JSON.stringify(doc , null, 2));
  //console.log('docid      : '+docid);

//FIXME  var cloudantOrError = getCloudantAccount(message);
//  if (typeof cloudantOrError !== 'object') {
//    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
//    return whisk.error('Internal error.  An unexpected object type was obtained.');
//  }
//  var cloudant = cloudantOrError;
//  var cloudantDb = cloudant.use(dbname);

  // Create and activate a new API path
  // 1. Create or update the API configuration in the DB
  // 2. Activate the entire API
  return configureApi(message.apidoc)
  .then(function(api) {
    console.log('API route configured successfully')
    return activateApi(doc.namespace, doc.gatewayBasePath)
  })
  .catch(function(reason) {
    console.error('API route creation failure: '+JSON.stringify(reason))
    return Promise.reject('API route creation failure: '+JSON.stringify(reason));
  });

  // Creating a new route
  // 1. Configure the API Gateway with a "tenant" (i.e. the namespace)
  // 2. Configure the API Gateway with the new API/route
  // 3. Add the API configuration to the OpenWhisk database
//  var retApi;
//  var tenantGuid;
//  var tenantAdded = false;
//  var routeAdded = false;
//  var dbUpdated = false;
//  return addTenantToGateway(gwInfo, doc.namespace)
//  .then(function(tenant){
//      if (tenant && tenant.id ) {
//        console.log('Tenant guid: '+tenant.id);
//        tenantGuid = tenant.id;
//      } else {
//        console.error('No tenant guid returned');
//        return Promise.reject('Internal error. API gateway did not return a tenant guid.')
//      }
//      tenantAdded = true;
//      doc.gatewayTenantId = tenantGuid;
//      return addRouteToGateway(gwInfo, doc); })
//  .then(function(apiRoute) {
//      if (apiRoute) {
//        console.log('API Gateway response: '+JSON.stringify(apiRoute));
//        retApi = apiRoute;
//      } else {
//        console.error('No configured API route returned');
//        return Promise.reject('Internal error.  API gateway did not return a configured API route.')
//      }
//      routeAdded = true;
//      doc.gwApiUrl = apiRoute.managedUrl;
//      doc.gwApiGuid = apiRoute.id;
//      return insert(cloudantDb, doc, docid); })
//  .then(function(insertResp) {
//      dbUpdated = true;
//      return Promise.resolve(retApi); })
//  .catch(function(reason) {
//      // FIXME MWD Possibly need to rollback some operations (i.e. rollback gateway config if db insert fails)
//      console.error('API creation failure: '+reason);
//      return Promise.reject('API creation failure: '+reason);
//  });
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
  var params = {
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
    },
    'swagger': apiPath.swagger
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

//FIXME function getCloudantAccount(message) {
//  // full cloudant URL - Cloudant NPM package has issues creating valid URLs
//  // when the username contains dashes (common in Bluemix scenarios)
//  var cloudantUrl;
//
//  if (message.url) {
//    // use bluemix binding
//    cloudantUrl = message.url;
//  } else {
//    if (!message.host) {
//      whisk.error('cloudant account host is required.');
//      return;
//    }
//    if (!message.username) {
//      whisk.error('cloudant account username is required.');
//      return;
//    }
//    if (!message.password) {
//      whisk.error('cloudant account password is required.');
//      return;
//    }
//    if (!message.port) {
//      whisk.error('cloudant account port is required.');
//      return;
//    }
//    if (!message.protocol) {
//      whisk.error('cloudant account protocol is required.');
//      return;
//    }
//
//    cloudantUrl = message.protocol + "://" + message.username + ":" + message.password + "@" + message.host + ":" + message.port;
//  }
//
//  return require('cloudant')({
//    url: cloudantUrl
//  });
//}
//
//function addTenantToGateway(gwInfo, namespace) {
//  var options = {
//    url: gwInfo.gwUrl+'/tenants',
//    agentOptions: {rejectUnauthorized: false},
//    headers: {
//      'Content-Type': 'application/json',
//      //'Authorization': 'Basic ' + 'btoa(gwInfo.gwAuth)',  // FIXME MWD Authentication
//    },
//    json: {
//      instance: 'openwhisk',    // Use a fixed instance so only 1 openwhisk tenant is ever created.
//      namespace: namespace
//    }
//  };
//  console.log('addTenantToGateway: request: '+JSON.stringify(options));
//
//  return new Promise(function(resolve, reject) {
//    request.put(options, function(error, response, body) {
//      var statusCode = response ? response.statusCode : undefined;
//      console.log('addTenantToGateway: response status: '+ statusCode);
//      error && console.error('Warning: addTenantToGateway request failed: '+JSON.stringify(error));
//      body && console.log('addTenantToGateway: response body: '+JSON.stringify(body));
//
//      if (error) {
//        console.error('addTenantToGateway: Unable to configure a tennant on the API Gateway: '+JSON.stringify(error))
//        reject('Unable to configure the API Gateway: '+JSON.stringify(error));
//      } else if (statusCode != 200) {
//        console.error('addTenantToGateway: failure: response code: '+statusCode)
//        reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
//      } else {
//        if (body && body.id) {  // { id: GUID, namespace: NAMESPACE, instance: 'whisk' }
//          resolve(body);
//        } else {
//          console.error('addTenantToGateway: failure: No tenant guid provided')
//          reject('Unable to configure the API Gateway: Invalid response from API Gateway');
//        }
//      }
//    });
//  });
//}

/**
 * Configures an API route on the API Gateway.  This API will map to an OpenWhisk action that
 * will be invoked by the API Gateway when the API route is accessed.
 *
 * @param gwInfo Required.
 * @param    gwUrl   Required.  The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth  Required.  The credentials used to access the API Gateway REST endpoints
 * @param payload  Required. A JSON object used as the request body
 * @param   payload.namespace  Required. The OpenWhisk namespace of the user defining this API route
 * @param   payload.gatewayPath  Required.  The relative path for this route
 * @param   payload.gatewayMethod  Required.  The gateway route REST verb
 * @param   payload.backendUrl  Required.  The full REST URL used to invoke the associated action
 * @param   payload.backendMethod  Required.  The REST verb used to invoke the associated action
 * @return A promise for an object describing the result with fields error and response
 */
//function addRouteToGateway(gwInfo, payload) {
//
//  var options = {
//    url: gwInfo.gwUrl+'/apis',
//    agentOptions: {rejectUnauthorized: false},
//    headers: {
//      'Content-Type': 'application/json',
//      //'Authorization': 'Basic ' + 'btoa(gwInfo.gwAuth)',  // FIXME MWD Authentication
//    },
//    json: payload,
//  };
//  console.log('addRouteToGateway: request: '+JSON.stringify(options));
//
//  return new Promise(function(resolve, reject) {
//    request.post(options, function(error, response, body) {
//      var statusCode = response ? response.statusCode : undefined;
//      console.log('addRouteToGateway: response status:'+ statusCode);
//      error && console.error('Warning: addRouteToGateway request failed: '+ JSON.stringify(error));
//      body && console.log('addRouteToGateway: response body: '+JSON.stringify(body));
//
//      if (error) {
//        console.error('addRouteToGateway: Unable to configure the API Gateway: '+JSON.stringify(error))
//        reject('Unable to configure the API Gateway: '+JSON.stringify(error));
//      } else if (statusCode != 200) {
//        console.error('addRouteToGateway: Response code: '+statusCode)
//        reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
//      } else if (!body) {
//        console.error('addRouteToGateway: Unable to configure the API Gateway: No response body')
//        reject('Unable to configure the API Gateway: No response received from the API Gateway');
//      } else {
//        resolve(body);
//      }
//    });
//  });
//}

/**
 * Create document in database.
 */
//function insert(cloudantDb, doc, docid) {
//  return new Promise( function(resolve, reject) {
//    cloudantDb.insert(doc, docid, function(error, response) {
//      if (!error) {
//        console.log("success", response);
//        resolve(response);
//      } else {
//        console.log("error", JSON.stringify(error))
//        reject(JSON.stringify(error));  // FIXME MWD could not return the error object as it caused an exception
//      }
//    });
//  });
//}

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

  // TODO:  Validate that the action actually exists
  if(!tmpdoc.action) {
    return 'apidoc is missing the action (action name) field.';
  }

  if (!tmpdoc.action.backendMethod) {
    return 'action is missing the backendMethod field.';
  }

  if (!tmpdoc.action.backendUrl) {
    return 'action is missing the backendMethod field.';
  }

  if(!tmpdoc.action.namespace) {
    return 'action is missing the namespace field.';
  }

  if(!tmpdoc.action.name) {
    return 'action is missing the name field.';
  }

  if(!tmpdoc.action.authkey) {
    return 'action is missing the authkey field.';
  }

  return '';
}