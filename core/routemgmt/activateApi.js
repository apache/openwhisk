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
 * Activate an API on the API Gateway
 *
 * Parameters (all as fields in the message JSON object)
 *   host       Required. The database dns host name
 *   port       Required. The database port number
 *   protocol   Required. The database protocol (i.e. http, https)
 *   dbname     Required. The name of the database
 *   username   Required. The database user name used to access the database
 *   password   Required. The database user password
 *   gwUrl      Required. The API Gateway base path (i.e. http://gw.com)
 *   gwUser     Optional. The API Gateway user ID
 *   gwAuth     Optional. The API Gateway user ID's password
 *   namespace  Required. The namespace of the API to be activated
 *   basepath   Required. The basepath of the API to be activated
 *   docid      Optional. If specified, use this docid to retrieve API doc from DB
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password, gwUrl, gwUser, gwPwd
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
 var request = require('request');
 var url = require('url');

function main(message) {
  var badArgMsg = '';
  if (badArgMsg = validateArgs(message)) {
    return whisk.error(badArgMsg);
  }
  var dbname = message.dbname;

  var gwInfo = {
    gwUrl: message.gwUrl,
  };
  if (message.gwUser && message.gwPwd) {
    gwInfo.gwAuth = Buffer.from(message.gwUser+':'+message.gwPwd,'ascii').toString('base64');
  }

  // Log parameter values
  console.log('DB host    : '+message.host);
  console.log('DB port    : '+message.port);
  console.log('DB protocol: '+message.protocol);
  console.log('DB username: '+confidentialPrint(message.username));
  console.log('DB password: '+confidentialPrint(message.password));
  console.log('DB database: '+message.dbname);
  console.log('GW URL     : '+message.gwUrl);
  console.log('GW User    : '+confidentialPrint(message.gwUser));
  console.log('GW Pwd     : '+confidentialPrint(message.gwPwd));
  console.log('namespace  : '+message.namespace);
  console.log('basepath   : '+message.basepath);
  console.log('docid      : '+message.docid);

  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return whisk.error('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(dbname);

  // Activating a specified API
  // 1. Pull the API's configuration document from the DB
  // 2. Create the request document to send to the API Gateway
  // 3. Send the request to the API Gateway to configure and activate the API
  // 4. If successful, update the API configuration in the DB to indicate the activation status
  var tenantGuid;
  var tenantAdded = false;
  var gwApiActivated = false;
  var dbUpdated = false;
  var gwApiDoc;
  var dbApiDoc;

  return getApiDoc(message.namespace, message.basepath, message.docid)
  .then(function(dbdoc) {
    dbApiDoc = dbdoc;
    gwApiDoc = makeGwApiDoc(dbdoc);
    console.log("Gateway doc: "+JSON.stringify(gwApiDoc));
    return addTenantToGateway(gwInfo, message.namespace);
  })
  .then(function(tenant){
      if (tenant && tenant.id ) {
        console.log('Tenant guid: '+tenant.id);
        tenantGuid = tenant.id;
        gwApiDoc.tenantId = tenant.id;
      } else {
        console.error('No tenant guid returned');
        return Promise.reject('Internal error. API gateway did not return a tenant guid.')
      }
      tenantAdded = true;
      return addRouteToGateway(gwInfo, gwApiDoc);
  })
  .then(function(gwApiResponse) {
      if (gwApiResponse) {
        console.log('API Gateway response: '+JSON.stringify(gwApiResponse));
      } else {
        console.error('No configured API route returned');
        return Promise.reject('Internal error.  API gateway did not return a configured API route.')
      }
      gwApiActivated = true;
      dbApiDoc.gwApiUrl = gwApiResponse.managedUrl;
      var gwUrl = url.parse(gwApiResponse.managedUrl);
      dbApiDoc.apidoc.host = gwUrl.host;
      dbApiDoc.gwApiGuid = gwApiResponse.id;
      dbApiDoc.tenantId = tenantGuid;
      dbApiDoc.gwApiActivated = true;
      console.log('Updating DB API doc: '+JSON.stringify(dbApiDoc));
      return updateApiDocInDb(cloudantDb, dbApiDoc); })
  .then(function(insertResp) {
      dbUpdated = true;
      return Promise.resolve(dbApiDoc);
  })
  .catch(function(reason) {
      if (!gwApiActivated) {
        console.error('API activation failure: '+reason);
        return Promise.reject('API activation failure: '+reason);
      }
      console.error('API was activated; however, the database could not be udpated: '+reason);
      return Promise.reject('API was activated; however, an internal error occurred after activation: '+reason);
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

function addTenantToGateway(gwInfo, namespace) {
  var options = {
    url: gwInfo.gwUrl+'/tenants',
    headers: {
      'Accept': 'application/json'
    },
    json: {                     // Auto set header: 'Content-Type': 'application/json'
      instance: 'openwhisk',    // Use a fixed instance so all openwhisk tenants have a common instance
      namespace: namespace
    }
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }
  console.log('addTenantToGateway: request: '+JSON.stringify(options));

  return new Promise(function(resolve, reject) {
    request.put(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('addTenantToGateway: response status: '+ statusCode);
      error && console.error('Warning: addTenantToGateway request failed: '+JSON.stringify(error));
      body && console.log('addTenantToGateway: response body: '+JSON.stringify(body));

      if (error) {
        console.error('addTenantToGateway: Unable to configure a tennant on the API Gateway: '+JSON.stringify(error))
        reject('Unable to configure the API Gateway: '+JSON.stringify(error));
      } else if (statusCode != 200) {
        console.error('addTenantToGateway: failure: response code: '+statusCode)
        reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
      } else {
        if (body && body.id) {  // body has format like:  { id: GUID, namespace: NAMESPACE, instance: 'openwhisk' }
          console.log('addTenantToGateway: got a single tenant resposne');
          resolve(body);
        } else {
          console.error('addTenantToGateway: failure: No tenant guid provided')
          reject('Unable to configure the API Gateway: Invalid response from API Gateway');
        }
      }
    });
  });
}

/**
 * Configures an API route on the API Gateway.  This API will map to an OpenWhisk action that
 * will be invoked by the API Gateway when the API route is accessed.
 *
 * @param gwInfo Required.
 * @param    gwUrl   Required.  The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth  Required.  The credentials used to access the API Gateway REST endpoints
 * @param gwApiDoc   Required. The gateway API object to send to the API gateway
 * @param   payload.namespace  Required. The OpenWhisk namespace of the user defining this API route
 * @param   payload.gatewayPath  Required.  The relative path for this route
 * @param   payload.gatewayMethod  Required.  The gateway route REST verb
 * @param   payload.backendUrl  Required.  The full REST URL used to invoke the associated action
 * @param   payload.backendMethod  Required.  The REST verb used to invoke the associated action
 * @return A promise for an object describing the result with fields error and response
 */
function addRouteToGateway(gwInfo, gwApiDoc) {
  var requestFcn = request.post;

  var options = {
    url: gwInfo.gwUrl+'/apis',
    headers: {
      'Accept': 'application/json'
    },
    json: gwApiDoc,  // Auto set header: 'Content-Type': 'application/json'
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }

  if (gwApiDoc.id) {
    console.log("addRouteToGateway: Updating existing API");
    options.url = gwInfo.gwUrl+'/apis/'+gwApiDoc.id;
    requestFcn = request.put;
  }
  console.log('addRouteToGateway: request: '+JSON.stringify(options, " ", 2));

  return new Promise(function(resolve, reject) {
    requestFcn(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('addRouteToGateway: response status:'+ statusCode);
      error && console.error('Warning: addRouteToGateway request failed: '+ JSON.stringify(error));
      body && console.log('addRouteToGateway: response body: '+JSON.stringify(body));

      if (error) {
        console.error('addRouteToGateway: Unable to configure the API Gateway: '+JSON.stringify(error))
        reject('Unable to configure the API Gateway: '+JSON.stringify(error));
      } else if (statusCode != 200) {
        console.error('addRouteToGateway: Response code: '+statusCode)
        reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
      } else if (!body) {
        console.error('addRouteToGateway: Unable to configure the API Gateway: No response body')
        reject('Unable to configure the API Gateway: No response received from the API Gateway');
      } else {
        resolve(body);
      }
    });
  });
}

/**
 * Update an existing document in database.
 * @param cloudantDb  The DB object
 * @param doc  The document to write.  It should have _id and _rev fields.
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


function makeGwApiDoc(api) {
  var gwdoc = {};
  gwdoc.basePath = api.apidoc.basePath;
  gwdoc.name = api.apidoc.info.title;
  gwdoc.resources = {};
  if (api.gwApiGuid) {
    gwdoc.id = api.gwApiGuid;
  }
  for (var path in api.apidoc.paths) {
  console.log('Got dbapidoc path: ', JSON.stringify(path));
    gwdoc.resources[path] = {};
    var gwpathop = gwdoc.resources[path].operations = {};
    for (var operation in api.apidoc.paths[path]) {
      console.log('Got operation for path: ', operation);
      console.log('Got operation backendMethod: ', api.apidoc.paths[path][operation]['x-ibm-op-ext'].backendMethod);
      var gwop = gwpathop[operation] = {};
      gwop.backendMethod = api.apidoc.paths[path][operation]['x-ibm-op-ext'].backendMethod;
      gwop.backendUrl = api.apidoc.paths[path][operation]['x-ibm-op-ext'].backendUrl;
      gwop.policies = api.apidoc.paths[path][operation]['x-ibm-op-ext'].policies;
    }
  }
  return gwdoc;
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

  if (!message.namespace) {
    return 'namespace is required';
  }

  if (!message.basepath) {
    return 'basepath is required';
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
