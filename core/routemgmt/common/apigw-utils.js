/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Route management action common API GW utilities
 */
var request = require('request');
var _ = require('lodash');

const ApimgmtUserAgent = "OpenWhisk-apimgmt/1.0.0";
var UserAgent = ApimgmtUserAgent;

/**
 * Configures an API route on the API Gateway.  This API will map to an OpenWhisk action that
 * will be invoked by the API Gateway when the API route is accessed.
 *
 * @param gwInfo Required.
 * @param    gwUrl     Required. The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth    Required. The user bearer token used to access the API Gateway REST endpoints
 * @param spaceGuid    Required. User's space guid.  APIs are stored under this context
 * @param swaggerApi   Required. The API swagger object to send to the API gateway
 * @param apiId        Required. API id. When specified, the API exists and will be updated; otherwise the API is created anew
 * @return A promise for an object describing the result with fields error and response
 */
function addApiToGateway(gwInfo, spaceGuid, swaggerApi, apiId) {
  var requestFcn = request.post;

  console.log('addApiToGateway: ');
  try {
  var options = {
    followAllRedirects: true,
    url: gwInfo.gwUrl+'/'+encodeURIComponent(spaceGuid) + '/apis',
    json: swaggerApi,  // Use of json automatically sets header: 'Content-Type': 'application/json'
    headers: {
      'User-Agent': UserAgent
    }
  };
  if (gwInfo.gwAuth) {
    _.set(options, "headers.Authorization", 'Bearer ' + gwInfo.gwAuth);
  }

  if (apiId) {
    console.log("addApiToGateway: Updating existing API");
    options.url = gwInfo.gwUrl + '/' + encodeURIComponent(spaceGuid) + '/apis/' + encodeURIComponent(apiId);
    requestFcn = request.put;
  }

  console.log('addApiToGateway: request: '+JSON.stringify(options, " ", 2));
  }
  catch (e) {
    console.error('addApiToGateway exception: '+e);
  }
  return new Promise(function(resolve, reject) {
    requestFcn(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('addApiToGateway: response status:'+ statusCode);
      if (error) console.error('Warning: addRouteToGateway request failed: '+ makeJsonString(error));
      if (response && response.headers) console.log('addApiToGateway: response headers: '+makeJsonString(response.headers));
      if (body) console.log('addApiToGateway: response body: '+makeJsonString(body));
      if (error) {
        console.error('addApiToGateway: Unable to configure the API Gateway');
        reject('Unable to configure the API Gateway: '+makeJsonString(error));
      } else if (statusCode != 200) {
        if (body) {
          var errMsg = makeJsonString(body);
          if (body.error && body.error.message) errMsg = body.error.message;
          reject('Unable to configure the API Gateway (status code '+statusCode+'): '+ errMsg);
        } else {
          reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
        }
      } else if (!body) {
        console.error('addApiToGateway: Unable to configure the API Gateway: No response body');
        reject('Unable to configure the API Gateway: No response received from the API Gateway');
      } else {
        resolve(body);
      }
    });
  });
}

/**
 * Removes an API route from the API Gateway.
 *
 * @param gwInfo     Required.
 * @param    gwUrl   Required. The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth  Optional. The credentials used to access the API Gateway REST endpoints
 * @param spaceGuid  Required. User's space guid.  APIs are stored under this context
 * @param apiId      Required.  API basepath.  Unique per spaceGuid
 * @return A promise for an object describing the result with fields error and response
 */
function deleteApiFromGateway(gwInfo, spaceGuid, apiId) {
  var options = {
    followAllRedirects: true,
    url: gwInfo.gwUrl+'/'+encodeURIComponent(spaceGuid)+'/apis/'+encodeURIComponent(apiId),
    agentOptions: {rejectUnauthorized: false},
    headers: {
      'Accept': 'application/json',
      'User-Agent': UserAgent
    }
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Bearer ' + gwInfo.gwAuth;
  }
  console.log('deleteApiFromGateway: request: '+JSON.stringify(options));

  return new Promise(function(resolve, reject) {
    request.delete(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('deleteApiFromGateway: response status:'+ statusCode);
      if (error) console.error('Warning: deleteGatewayApi request failed: '+ makeJsonString(error));
      if (body) console.log('deleteApiFromGateway: response body: '+makeJsonString(body));
      if (response && response.headers) console.log('deleteApiFromGateway: response headers: '+makeJsonString(response.headers));
      if (error) {
        console.error('deleteApiFromGateway: Unable to delete the API Gateway');
        reject('Unable to delete the API Gateway: '+makeJsonString(error));
      } else if (statusCode != 200  && statusCode != 204) {
        if (body) {
          var errMsg = makeJsonString(body);
          if (body.error && body.error.message) errMsg = body.error.message;
          reject('Unable to delete the API Gateway (status code '+statusCode+'): '+ errMsg);
        } else {
          reject('Unable to delete the API Gateway: Response failure code: '+statusCode);
        }
      } else {
        resolve();
      }
    });
  });
}

/**
 * Return an array of APIs
 */
function getApis(gwInfo, spaceGuid, bpOrApiName) {
  var qsBasepath = { 'basePath' : bpOrApiName };
  var qsApiName = { 'title' : bpOrApiName };
  var qs;
  if (bpOrApiName) {
    if (bpOrApiName.indexOf('/') !== 0) {
      console.log('getApis: querying APIs based on api name');
      qs = qsApiName;
    } else {
      console.log('getApis: querying APIs based on basepath');
      qs = qsBasepath;
    }
  }
  var options = {
    followAllRedirects: true,
    url: gwInfo.gwUrl+'/'+encodeURIComponent(spaceGuid)+'/apis',
    headers: {
      'Accept': 'application/json',
      'User-Agent': UserAgent
    },
    json: true
  };
  if (qs) {
    options.qs = qs;
  }
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Bearer ' + gwInfo.gwAuth;
  }
  console.log('getApis: request: '+JSON.stringify(options));

  return new Promise(function(resolve, reject) {
    request.get(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('getApis: response status: '+ statusCode);
      if (error) console.error('Warning: getApis request failed: '+makeJsonString(error));
      if (response && response.headers) console.log('getApis: response headers: '+makeJsonString(response.headers));
      console.log('getApis: body type = '+typeof body);
      if (body) console.log('getApis: response JSON.stringify(body): '+makeJsonString(body));
      if (error) {
        console.error('getApis: Unable to obtain API(s) from the API Gateway');
        reject('Unable to obtain API(s) from the API Gateway: '+makeJsonString(error));
      } else if (statusCode != 200) {
        console.error('getApis: failure: response code: '+statusCode);
        if (body) {
          var errMsg = makeJsonString(body);
          if (body.error && body.error.message) errMsg = body.error.message;
          reject('Unable to obtain API(s) from the API Gateway (status code '+statusCode+'): '+ errMsg);
        } else {
          reject('Unable to obtain API(s) from the API Gateway: Response failure code: '+statusCode);
        }
      } else {
        if (body) {
          if (Array.isArray(body)) {
            resolve(body);
          } else {
            console.error('getApis: Invalid API GW response body; a JSON array was not returned');
            resolve( [] );
          }
        } else {
          console.log('getApis: No APIs found');
          resolve( [] );
        }
      }
    });
  });
}

/*
 * Convert API object array into specified format
 * Parameters:
 *  apis    : array of 0 or more APIs
 *  format  : 'apigw' or 'swagger'
 * Returns:
 *  array   : New array of API object - each in the specified format
 */
function transformApis(apis, format) {
  var apisOutput;
  try {
    if (format.toLowerCase() === 'apigw') {
      apisOutput = apis;
    } else if (format.toLowerCase() === 'swagger') {
      apisOutput = JSON.parse(JSON.stringify(apis));
      for (var i = 0; i < apisOutput.length; i++) {
        apisOutput[i] = generateSwaggerApiFromGwApi(apisOutput[i]);
      }
    } else {
      console.error('transformApis: Invalid format specification: '+format);
      throw 'Internal error. Invalid format specification: '+format;
    }
  } catch(e) {
    console.error('transformApis: exception caught: '+e);
    throw 'API format transformation error: '+e;
  }

  return apisOutput;
}

/*
 * Convert API object into swagger JSON format
 * Parameters:
 *  gwApi  : API object as returned from the API Gateway
 * Returns:
 *  object : New API object in swagger JSON format
 */
function generateSwaggerApiFromGwApi(gwApi) {
  // Start with a copy of the gwApi object.  It's close to the desired swagger format
  var swaggerApi = JSON.parse(JSON.stringify(gwApi));
  swaggerApi.swagger = '2.0';
  swaggerApi.info = {
    title: gwApi.name,
    version: '1.0.0'
  };

  // Copy the gwAPI's 'resources' object as the starting point for the swagger 'paths' object
  swaggerApi.paths = JSON.parse(JSON.stringify(gwApi.resources));
  for (var path in swaggerApi.paths) {
    if (!swaggerApi.paths[path]) {
      console.error('generateSwaggerApiFromGwApi: no operations defined for ignored relpath \''+path+'\'');
      delete swaggerApi.paths[path];
      continue;
    }
    for (var op in swaggerApi.paths[path].operations) {
      console.log('generateSwaggerApiFromGwApi: processing path '+path+'; operation '+op);
      if (!op) {
        console.error('generateSwaggerApiFromGwApi: path \''+path+'\' has no operations!');
        continue;
      }
      // swagger wants lower case operations
      var oplower = op.toLowerCase();

      // Valid swagger requires a 'responses' object for each operation
      swaggerApi.paths[path][oplower] = {
        responses: {
          default: {
            description: 'Default response'
          }
        }
      };
      // Custom swagger extension to hold the action mapping configuration
      swaggerApi.paths[path][oplower]['x-ibm-op-ext'] = {
        backendMethod : swaggerApi.paths[path].operations[op].backendMethod,
        backendUrl : swaggerApi.paths[path].operations[op].backendUrl,
        policies : JSON.parse(JSON.stringify(swaggerApi.paths[path].operations[op].policies)),
        actionName: getActionNameFromActionUrl(swaggerApi.paths[path].operations[op].backendUrl),
        actionNamespace: getActionNamespaceFromActionUrl(swaggerApi.paths[path].operations[op].backendUrl)
      };
    }
    delete swaggerApi.paths[path].operations;
  }
  delete swaggerApi.resources;
  delete swaggerApi.name;
  delete swaggerApi.id;
  delete swaggerApi.managedUrl;
  delete swaggerApi.tenantId;
  return swaggerApi;
}

/*
 * Take an API in JSON swagger format and create an API GW compatible
 * API configuration JSON object
 * Parameters:
 *   swaggerApi - JSON object defining API in swagger format
 * Returns:
 *   gwApi      - JSON object defining API in API GW format
 */
function generateGwApiFromSwaggerApi(swaggerApi) {
  var gwApi = {};
  gwApi.basePath = swaggerApi.basePath;
  gwApi.name = swaggerApi.info.title;
  gwApi.resources = {};
  for (var path in swaggerApi.paths) {
  console.log('generateGwApiFromSwaggerApi: processing swaggerApi path: ', path);
    gwApi.resources[path] = {};
    var gwpathop = gwApi.resources[path].operations = {};
    for (var operation in swaggerApi.paths[path]) {
      console.log('generateGwApiFromSwaggerApi: processing swaggerApi operation: ', operation);
      console.log('generateGwApiFromSwaggerApi: processing operation backendMethod: ', swaggerApi.paths[path][operation]['x-ibm-op-ext'].backendMethod);
      var gwop = gwpathop[operation] = {};
      gwop.backendMethod = swaggerApi.paths[path][operation]['x-ibm-op-ext'].backendMethod;
      gwop.backendUrl = swaggerApi.paths[path][operation]['x-ibm-op-ext'].backendUrl;
      gwop.policies = swaggerApi.paths[path][operation]['x-ibm-op-ext'].policies;
    }
  }
  return gwApi;
}

/*
 * Create a base swagger API object containing the API basepath, but no endpoints
 * Parameters:
 *   basepath   - Required. API basepath
 *   apiname    - Optional. API friendly name. Defaults to basepath
 * Returns:
 *   swaggerApi - API swagger JSON object
 */
function generateBaseSwaggerApi(basepath, apiname) {
  var swaggerApi = {
    'swagger': '2.0',
    'info': {
      'title': apiname || basepath,
      'version': '1.0.0'
    },
    'basePath': basepath,
    'paths': {},
    'x-ibm-configuration': {
      'assembly': {
      },
      'cors': {
        'enabled': true
      }
    }
  };
  return swaggerApi;
}

/*
 * Take an existing API in JSON swagger format, and update it with a single path/operation.
 * The addition can be an entirely new path or a new operation under an existing path.
 * Parameters:
 *   swaggerApi - API to augment in swagger JSON format.  This will be updated.
 *   endpoint   - JSON object describing new path/operation.  Required fields
 *                {
 *                  gatewayMethod:
 *                  gatewayPath:
 *                  action: {
 *                    authkey:
 *                    backendMethod:
 *                    backendUrl:
 *                    name:
 *                    namespace:
 *                  }
 *                }
 *   responsetype Optional. The web action invocation .extension.  Defaults to json
 * Returns:
 *   swaggerApi - Input JSON object in swagger format containing the union of swaggerApi + new path/operation
 */
function addEndpointToSwaggerApi(swaggerApi, endpoint, responsetype) {
  var operation = endpoint.gatewayMethod.toLowerCase();
  var operationId = makeOperationId(operation, endpoint.gatewayPath);
  responsetype = responsetype || 'json';
  console.log('addEndpointToSwaggerApi: operationid = '+operationId);
  try {
    var auth_base64 = Buffer.from(endpoint.action.authkey,'ascii').toString('base64');

    // If the relative path already exists, append to it; otherwise create it
    if (!swaggerApi.paths[endpoint.gatewayPath]) {
      swaggerApi.paths[endpoint.gatewayPath] = {};
    }

    swaggerApi.paths[endpoint.gatewayPath][operation] = {
      'operationId': operationId,
      'x-openwhisk': {
        'url': makeWebActionBackendUrl(endpoint.action, responsetype),
        'namespace': endpoint.action.namespace,
        'package': getPackageNameFromFqActionName(endpoint.action.name),
        'action': getActionNameFromFqActionName(endpoint.action.name),
      },
      'responses': {
        'default': {
          'description': 'Default response'
        }
      }
    };

    // API GW extensions
    console.log('addEndpointToSwaggerApi: setting api gw extension values');
    setActionOperationInvocationDetails(swaggerApi, endpoint, operationId, responsetype);
  }
  catch(e) {
    console.log("addEndpointToSwaggerApi: exception "+e);
    throw 'API swagger generation error: '+e;
  }

  return swaggerApi;
}

function setActionOperationInvocationDetails(swagger, endpoint, operationId, responsetype) {
  var caseArr = _.get(swagger, 'x-ibm-configuration.assembly.execute[0].operation-switch.case') || [];
  var caseIdx = getCaseOperationIdx(caseArr, operationId);
  var operations = [operationId];
  _.set(swagger, 'x-ibm-configuration.assembly.execute[0].operation-switch.case['+caseIdx+'].operations', operations);
  _.set(swagger, 'x-ibm-configuration.assembly.execute[0].operation-switch.case['+caseIdx+'].execute[0].invoke.target-url',  makeWebActionBackendUrl(endpoint.action, responsetype));
  _.set(swagger, 'x-ibm-configuration.assembly.execute[0].operation-switch.case['+caseIdx+'].execute[0].invoke.verb', 'keep');
}

// Return the numeric index into case[] into which the associated operation will be configured
// If the array is empty, the returned index is 0
// If the operation exists, the existing index will be returned
// Otherwise the index will be the last existing index + 1
function getCaseOperationIdx(caseArr, operationId) {
  var i;
  for (i=0; i<caseArr.length; i++) {
    if (caseArr[i].operations[0] == operationId) {
      console.log('getCaseOperationIdx: found existing operation for '+operationId+' at case index '+i);
      break;
    }
  }
  return i;
}

// Create the external URL used to invoke a web-action.  Examples:
// - https://localhost/api/v1/web/whisk.system/default/echo-web.json
// - https://localhost/api/v1/web/whisk.system/mypkg/echo-web.json
// NOTE: Use "default" as the package name when a package is not explicitly defined.
// Parameters
//   endpointAction       - fully qualified action name (i.e. /ns/pkg/action or /ns/action)
//   endpointResponseType - determines the action invocation extension without the '.' (i.e. http, json, etc)
// Returns:
//   string               - web-action URL
function makeWebActionBackendUrl(endpointAction, endpointResponseType) {
  host = getHostFromActionUrl(endpointAction.backendUrl);
  ns = endpointAction.namespace;
  pkg = getPackageNameFromFqActionName(endpointAction.name) || 'default';
  name = getActionNameFromFqActionName(endpointAction.name);
  return 'https://' + host + '/api/v1/web/' + ns + '/' + pkg + '/' + name + '.' + endpointResponseType;
}

/*
 * Update an existing Swagger API document by removing the specified relpath/operation section.
 *   swaggerApi - API from which to remove the specified endpoint.  This object will be updated.
 *   endpoint   - JSON object describing new path/operation.  Required fields
 *                {
 *                  gatewayPath:    Optional.  The relative path.  If not provided, the original swaggerApi is returned
 *                  gatewayMethod:  Optional.  The operation under gatewayPath.  If not provided, the entire gatewayPath is deleted.
 *                                             If updated gatewayPath has no more operations, then the entire gatewayPath is deleted.
 *                }
 * @returns Updated JSON swagger API
 */
function removeEndpointFromSwaggerApi(swaggerApi, endpoint) {
  var relpath = endpoint.gatewayPath;
  var operation = endpoint.gatewayMethod ? endpoint.gatewayMethod.toLowerCase() : endpoint.gatewayMethod;
  console.log('removeEndpointFromSwaggerApi: relpath '+relpath+' operation '+operation);
  if (!relpath) {
      console.log('removeEndpointFromSwaggerApi: No relpath specified; nothing to remove');
      return 'No path provided; nothing to remove';
  }

  // If an operation is not specified, delete the entire relpath
  if (!operation) {
      console.log('removeEndpointFromSwaggerApi: No operation; removing entire relpath '+relpath);
      if (swaggerApi.paths[relpath]) {
          for (var op in swaggerApi.paths[relpath]) {
            deleteActionOperationInvocationDetails(swaggerApi, makeOperationId(op, relpath));
          }
          delete swaggerApi.paths[relpath];
      } else {
          console.log('removeEndpointFromSwaggerApi: relpath '+relpath+' does not exist in the API');
          return 'path \''+relpath+'\' does not exist in the API';
      }
  } else { // relpath and operation are specified, just delete the specific operation
      if (swaggerApi.paths[relpath] && swaggerApi.paths[relpath][operation]) {
          delete swaggerApi.paths[relpath][operation];
          if (Object.keys(swaggerApi.paths[relpath]).length === 0) {
            console.log('removeEndpointFromSwaggerApi: after deleting operation '+operation+', relpath '+relpath+' has no more operations; so deleting entire relpath '+relpath);
            delete swaggerApi.paths[relpath];
          }
          deleteActionOperationInvocationDetails(swaggerApi, makeOperationId(operation, relpath));
      } else {
          console.log('removeEndpointFromSwaggerApi: relpath '+relpath+' with operation '+operation+' does not exist in the API');
          return 'path \''+relpath+'\' with operation \''+operation+'\' does not exist in the API';
      }
  }

  return swaggerApi;
}

function deleteActionOperationInvocationDetails(swagger, operationId) {
  console.log('deleteActionOperationInvocationDetails: deleting case entry for ' + operationId);
  var caseArr = _.get(swagger, 'x-ibm-configuration.assembly.execute[0].operation-switch.case') || [];
  if (caseArr.length > 0) {
    var caseIdx = getCaseOperationIdx(caseArr, operationId);
    _.pullAt(caseArr, caseIdx);
    _.set(swagger, 'x-ibm-configuration.assembly.execute[0].operation-switch.case', caseArr);
  } else {
    console.log('deleteActionOperationInvocationDetails: empty case[] array; case operation '+operationId+' does not exist');
  }
}

function confidentialPrint(str) {
    var printStr;
    if (str) {
        printStr = 'XXXXXXXXXX';
    }
    return printStr;
}

/* Create the CLI response payload from an array of GW API objects
 * Parameters:
 *  gwApis    - Array of JSON GW API objects
 * Returns:
 *  respApis  - A new array of JSON CLI API objects
 */
function generateCliResponse(gwApis) {
  var respApis = [];
  try {
    for (var i=0; i<gwApis.length; i++) {
      respApis.push(generateCliApiFromGwApi(gwApis[i]));
    }
  } catch(e) {
    console.error('generateCliResponse: exception caught: '+e);
    throw 'API format transformation error: '+e;
  }
  return respApis;
}

/* Use the specified GW API object to create an API JSON object in for format the CLI expects.
 * Parameters:
 *  gwApi      - JSON GW API object
 * Returns:
 *  cliApi     - JSON CLI API object
 */
function generateCliApiFromGwApi(gwApi) {
  console.log('generateCliApiFromGwApi: ' + JSON.stringify(gwApi, " ", 2));
  var cliApi = {};
  cliApi.id = 'Not Used';
  cliApi.key = 'Not Used';
  cliApi.value = {};
  cliApi.value.namespace = 'Not Used';
  cliApi.value.gwApiActivated = true;
  cliApi.value.tenantId = 'Not Used';
  cliApi.value.gwApiUrl = gwApi.managed_url;
  cliApi.value.apidoc = gwApi.open_api_doc;
  return cliApi;
}

/*
 * Parses the openwhisk action URL and returns the various components
 * Parameters
 *  url    - in format PROTOCOL://HOST/api/v1/web/NAMESPACE/PACKAGE/ACTION.http
 * Returns
 *  result - an array of strings.
 *           result[0] : Entire URL
 *           result[1] : protocol (i.e. https)
 *           result[2] : host (i.e. myco.com, 1.2.3.4)
 *           result[3] : namespace
 *           result[4] : package name
 *           result[5] : action name
 *           result[6] : action response type (i.e http, json, text, html, or svg)
 */
function parseActionUrl(actionUrl) {
  console.log('parseActionUrl: parsing action url: '+actionUrl);
  var actionUrlPattern = /(\w+):\/\/([:\w.\-]+)\/api\/v\d\/web\/([@\w .\-]+)\/([@\w .\-]+)\/([@\w .\-\/]+)\.(\w+)/;
  try {
    return actionUrl.match(actionUrlPattern);
  } catch(e) {
    console.error('parseActionUrl: exception: '+e);
    throw 'parseActionUrl: exception: '+e;
  }
}

/*
 * https://172.17.0.1/api/v1/web/NAMESPACE/PACKAGE/ACTION.json
 * would return ACTION
 */
function getActionNameFromActionUrl(actionUrl) {
  return parseActionUrl(actionUrl)[5];
}

/*
 * https://172.17.0.1/api/v1/web/NAMESPACE/PACKAGE/ACTION.json
 * would return NAMESPACE
 */
function getPackageNameFromActionUrl(actionUrl) {
  return parseActionUrl(actionUrl)[4];
}

/*
 * https://172.17.0.1/api/v1/web/NAMESPACE/PACKAGE/ACTION.json
 * would return NAMESPACE
 */
function getActionNamespaceFromActionUrl(actionUrl) {
  return parseActionUrl(actionUrl)[3];
}

/*
 * https://172.17.0.1/api/v1/namespaces/whisk.system/actions/getaction
 * would return 172.17.0.1
 * https://my-host.mycompany.com/api/v1/namespaces/myid@gmail.com_dev/actions/mypkg/getaction
 * would return my-host.mycompany.com
 */
function getHostFromActionUrl(actionUrl) {
  return parseActionUrl(actionUrl)[2];
}

/*
 * Parses an openwhisk action name into its various components
 * Parameters
 *  fqname - in one of the following formats:
 *           (1)   /[namespace]/[package]/[action]
 *           (2)   [package]/[action]
 *           (3)   [action]
 * Returns
 *  result - an array of strings; depending on input
 *           Input (1):
 *             result[0] : fqname (i.e. /ns/pkg/action)
 *             result[1] : namespace
 *             result[2] : package
 *             result[3] : action name
 *           Input (2):
 *             result[0] : fqname (i.e.  pkg/action)
 *             result[1] : package
 *             result[2] : action name
 *             result[3] : ''
 *           Input (3):
 *             result[0] : fqname   (i.e. action)
 *             result[1] : action name
 *             result[2] : ''
 *             result[3] : ''
 */
function parseActionName(fqname) {
  console.log('parseActionName: parsing action: '+fqname);
  var actionNamePattern = /[\/]?([@ .\-\w]*)[\/]?([@ .\-\w]*)[\/]?([@ .\-\w]*)/;
  try {
    return fqname.match(actionNamePattern);
  } catch(e) {
    console.error('parseActionName: exception: '+e);
    throw 'parseActionName: exception: '+e;
  }
}

function getNamespaceFromFqActionName(fqAction) {
  var ns = '';
  var parsedAction = parseActionName(fqAction);
  if (parsedAction[3].length > 0) {
    ns = parsedAction[1];
  }
  return ns;
}

function getPackageNameFromFqActionName(fqAction) {
  var pkg = '';
  var parsedAction = parseActionName(fqAction);
  if (parsedAction[3].length > 0) {
    pkg = parsedAction[2];
  } else if (parsedAction[2].length > 0) {
    pkg = parsedAction[1];
  }
  return pkg;
}

function getActionNameFromFqActionName(fqAction) {
  var action = '';
  var parsedAction = parseActionName(fqAction);
  if (parsedAction[3].length > 0) {
    action = parsedAction[3];
  } else if (parsedAction[2].length > 0) {
    action = parsedAction[2];
  } else {
    action = parsedAction[1];
  }
  return action;
}

/*
 * Replace the namespace values that are used in the apidoc with the
 * specified namespace
 */
function updateNamespace(apidoc, namespace) {
  if (apidoc && namespace) {
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
  var namespacesPattern = /\/api\/v1\/web\/([\w@.-]+)\//;
  console.log('replaceNamespaceInUrl: namspace='+namespace+' url before - '+url);
  matchResult = url.match(namespacesPattern);
  if (matchResult !== null) {
    console.log('replaceNamespaceInUrl: replacing namespace \''+matchResult[1]+'\' with \''+namespace+'\'');
    url = url.replace(namespacesPattern, '/api/v1/web/'+namespace+'/');
  }
  console.log('replaceNamespaceInUrl: url after - '+url);
  return url;
}

/*
 * Take an error string and create a response object suitable for inclusion in
 * a Promise.reject() call.
 *
 * The response object can take two formats. If the api management action was
 * invoked as a web-action (i.e. via https://OW-HOST/api/v1/web/NS/PKG/ACTION.http),
 * then the response is an error object that mimics a non-webaction openwhisk
 * action's application error response - like so:
 *     {
 *        statusCode: 502,    <- signifies an application error
 *        headers: {'Content-Type': 'application/json'},
 *        body: Base64 encoded JSON error string
 *     }
 * Otherwise, the action was invoked as a regular OpenWhisk action
 * (i.e. https://OW-HOST/api/v1/namesapces/NS/actions/ACTION) and the
 * error response is just a string.  OpenWhisk backend logic will ultimately
 * convert this string into the above error object format.
 *
 * Parameters
 *  err             - Error string
 *  isWebAction     - Boolean. True -> generate a web-action response
 *                             False -> Generate an action response
 */
function makeErrorResponseObject(err, isWebAction) {
  console.log('makeErrorResponseObject: isWebAction: '+isWebAction);
  if (!isWebAction) {
    console.log('makeErrorResponseObject: not called as a web action');
    return err;
  }

  var bodystr;
  if (typeof err === 'string') {
    bodystr = JSON.stringify({
      "error": JSON.parse(makeJsonString(err)),  // Make sure err is plain old string to avoid duplicate JSON escaping
    });
  } else {
    bodystr = JSON.stringify(err);
  }
  return {
    statusCode: 502,
    headers: { 'Content-Type': 'application/json' },
    body: new Buffer(bodystr).toString('base64'),
  };
}

/*
 * Take an response string and create a response object suitable for inclusion in
 * a Promise.resolve() call.
 *
 * The response object can take two formats. If the api management action was
 * invoked as a web-action (i.e. via https://OW-HOST/api/v1/web/NS/PKG/ACTION.http),
 * then the response is an object that mimics a non-webaction openwhisk
 * action's application successful response - like so:
 *     {
 *        statusCode: 200,    <- signifies a successful action
 *        headers: {'Content-Type': 'application/json'},
 *        body: Base64 encoded JSON error string
 *     }
 * Otherwise, the action was invoked as a regular OpenWhisk action
 * (i.e. https://OW-HOST/api/v1/namesapces/NS/actions/ACTION) and the
 * response is just a string.  OpenWhisk backend logic will ultimately
 * convert this string into the above object format.
 *
 * Parameters
 *  err             - Error string
 *  isWebAction     - Boolean. True -> generate a web-action response
 *                             False -> generate an action response
 */
function makeResponseObject(resp, isWebAction) {
  console.log('makeResponseObject: isWebAction: '+isWebAction);
  if (!isWebAction) {
    console.log('makeErrorResponseObject: not called as a web action');
    return resp;
  }

  var bodystr;
  if (typeof resp === 'string') {
    bodystr = makeJsonString(resp);
  } else {
    bodystr = JSON.stringify(resp);
  }
  retobj = {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    body: new Buffer(bodystr).toString('base64')
  };
  return retobj;
}

/*
 * Take an object and serialize it into a JSON string.
 *
 * Special consideration is give to strings that are already JSON formatted since
 * serializing these strings can result in redundant escaping.
 *
 * If the value is simply not JSON compliant, a JSON error string is returned.
 */
function makeJsonString(x) {
  // If the value is not already a string, rely on JSON.stringify to convert it correctly
  if (typeof x != 'string') {
    try {
      return JSON.stringify(x);
    } catch (e) {
      console.error('makeJsonString: value cannot be JSON serialized: '+e);
      return e;
    }
  } else {
    // It's a string. If it's already a JSON formatted string, leave it alone
    // Otherwise, convert it into a JSON formatted string
    try {
      var temp = JSON.parse(x);
      return x;
    } catch (e) {
      // The string is not a JSON string, so convert it to a JSON string.
      console.log('makeJsonString: String is not JSON, so need to convert it: '+e);
      return JSON.stringify(x);
    }
  }
  return 'Unexpected JSON parsing failure';
}

/*
 * Generate and return a swagger OperationId value
 *
 * Parameters
 *   operation  - String. HTTP method (i.e. get, post, etc)
 *   repath     - String. Swagger path value. The path relative to the base path
 */
function makeOperationId(operation, relpath) {
   // Concatenate operation + relpath, stripping '/' and camelCasing after each '/' delimiter
   // relpath special character handling in each path segment:
   //   . ~ ! $ & ' ( ) * + , ; = : @ are removed and the following characters in the same path segment are camel cased
   //   - _  are retained and the following characters in the same path segment are lower cased
  return operation.toLowerCase() +
         relpath.replace(/[^0-9a-z_-]/gi, ' ').replace(/\w\S*/g, function(word) {return makeCamelCase(word);}).replace(/\s/g, '');
}

function makeCamelCase(str) {
  return str.charAt(0).toUpperCase() + str.substr(1).toLowerCase();
}

function setSubUserAgent(subAgent) {
  if (subAgent && subAgent.length > 0) {
    UserAgent = UserAgent + " " + subAgent;
  }
}

module.exports.getApis = getApis;
module.exports.addApiToGateway = addApiToGateway;
module.exports.deleteApiFromGateway = deleteApiFromGateway;
module.exports.generateBaseSwaggerApi = generateBaseSwaggerApi;
module.exports.generateGwApiFromSwaggerApi = generateGwApiFromSwaggerApi;
module.exports.transformApis = transformApis;
module.exports.generateSwaggerApiFromGwApi = generateSwaggerApiFromGwApi;
module.exports.addEndpointToSwaggerApi = addEndpointToSwaggerApi;
module.exports.removeEndpointFromSwaggerApi = removeEndpointFromSwaggerApi;
module.exports.confidentialPrint = confidentialPrint;
module.exports.generateCliResponse = generateCliResponse;
module.exports.generateCliApiFromGwApi = generateCliApiFromGwApi;
module.exports.updateNamespace = updateNamespace;
module.exports.makeErrorResponseObject = makeErrorResponseObject;
module.exports.makeResponseObject = makeResponseObject;
module.exports.makeJsonString = makeJsonString;
module.exports.setSubUserAgent = setSubUserAgent;
