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
 * Add a new or update an existing API configuration in the API Gateway
 * https://docs.cloudant.com/document.html#documentCreate
 *
 * Parameters (all as fields in the message JSON object)
 *   gwUrl                Required. The API Gateway base path (i.e. http://gw.com)
 *   gwUser               Optional. The API Gateway authentication
 *   gwPwd                Optional. The API Gateway authentication
 *   __ow_meta_namespace  Required. Namespace of API author; set by controller
 *                          Use this value to override namespace values in the apidoc
 *                          Don't override namespace values in the swagger though
 *   tenantInstance       Optional. Instance identifier used when creating the specific API GW Tenant
 *   apidoc               Required. The API Gateway mapping document
 *      namespace           Required.  Namespace of user/caller
 *      apiName             Optional if swagger not specified.  API descriptive name
 *      gatewayBasePath     Required if swagger not specified.  API base path
 *      gatewayPath         Required if swagger not specified.  Specific API path (relative to base path)
 *      gatewayMethod       Required if swagger not specified.  API path operation
 *      id                  Optional if swagger not specified.  Unique id of API
 *      action              Required. if swagger not specified
 *           name             Required.  Action name (includes package)
 *           namespace        Required.  Action namespace
 *           backendMethod    Required.  Action invocation REST verb.  "POST"
 *           backendUrl       Required.  Action invocation REST url
 *           authkey          Required.  Action invocation auth key
 *      swagger             Required if gatewayBasePath not provided.  API swagger JSON
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         gwUrl, gwAuth
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
var utils = require('./utils.js');

function main(message) {

  var badArgMsg = validateArgs(message);
  if (badArgMsg) {
    return Promise.reject(badArgMsg);
  }
  var gwInfo = {
    gwUrl: message.gwUrl,
  };
  if (message.gwUser && message.gwPwd) {
    gwInfo.gwAuth = Buffer.from(message.gwUser+':'+message.gwPwd,'ascii').toString('base64');
  }

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
  doc.swagger = swaggerObj;

  var basepath = getBasePath(doc);

  var tenantInstance = message.tenantInstance || 'openwhisk';

  // Log parameter values
  console.log('GW URL        : '+message.gwUrl);
  console.log('GW Auth       : '+utils.confidentialPrint(message.gwPwd));
  console.log('namespace     : '+doc.namespace);
  console.log('tenantInstance: '+message.tenantInstance+' / '+tenantInstance);
  console.log('API name      : '+doc.apiName);
  console.log('basepath      : '+doc.gatewayBasePath);
  console.log('relpath       : '+doc.gatewayPath);
  console.log('GW method     : '+doc.gatewayMethod);
  if (doc.action) {
    console.log('action name: '+doc.action.name);
    console.log('action namespace: '+doc.action.namespace);
    console.log('action backendUrl: '+doc.action.backendUrl);
    console.log('action backendMethod: '+doc.action.backendMethod);
    console.log('action authkey: '+utils.confidentialPrint(doc.action.authkey));
  }
  console.log('apidoc        :\n'+JSON.stringify(doc , null, 2));

  // Create and activate a new API path
  // 1. Create tenant id this namespace.  If id exists, create is a noop
  // 2. Obtain any existing configuration for the target API.  If none, this is a new API
  // 3. Create the API document to send to the API GW.  If API exists, update it
  // 4. Configure API GW with the new/updated API
  var tenantId;
  var gwApiId;
  return utils.createTenant(gwInfo, doc.namespace, tenantInstance)
  .then(function(tenant) {
    console.log('Got the API GW tenant: '+JSON.stringify(tenant));
    tenantId = tenant.id;
    return Promise.resolve(utils.getApis(gwInfo, tenant.id, basepath));
  })
  .then(function(apis) {
    console.log('Got '+apis.length+' APIs');
    if (apis.length === 0) {
      console.log('No APIs found for namespace '+doc.namespace+' with basepath '+basepath);
      return Promise.resolve(utils.generateBaseSwaggerApi(basepath, doc.apiName));
    } else if (apis.length > 1) {
      console.error('Multiple APIs found for namespace '+doc.namespace+' with basepath '+basepath);
      return Promise.reject('Internal error. Multiple APIs found for namespace '+doc.namespace+' with basepath '+basepath);
    }
    gwApiId = apis[0].id;
    return Promise.resolve(utils.generateSwaggerApiFromGwApi(apis[0]));
  })
  .then(function(swaggerApi) {
    if (doc.swagger) {
      console.log('Use provided swagger as the entire API; override any existing API');
      return Promise.resolve(doc.swagger);
    } else {
      console.log('Add the provided API endpoint');
      return Promise.resolve(utils.addEndpointToSwaggerApi(swaggerApi, doc));
    }
  })
  .then(function(swaggerApi) {
    console.log('Final swagger API config: '+ JSON.stringify(swaggerApi));
    return utils.addApiToGateway(gwInfo, tenantId, swaggerApi, gwApiId);
  })
  .then(function(gwApi) {
    console.log('API GW configured with API');
    var cliApi = utils.generateCliApiFromGwApi(gwApi).value;
    console.log('createApi success');
    return Promise.resolve(cliApi);
  })
  .catch(function(reason) {
    console.error('API creation failure: '+JSON.stringify(reason));
    return Promise.reject('API creation failure: '+JSON.stringify(reason));
  });
}


function getBasePath(apidoc) {
  if (apidoc.swagger) {
    return apidoc.swagger.basePath;
  }
  return apidoc.gatewayBasePath;
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
  if (matchResult !== null) {
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

  if (!message.gwUrl) {
    return 'gwUrl is required.';
  }

  if (!message.__ow_meta_namespace) {
    return '__ow_meta_namespace is required.';
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

 var tmpSwaggerDoc;
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

module.exports.main = main;
