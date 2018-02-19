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
 * Route management action common utilities
 */
var request = require('request');
var utils2 = require('./apigw-utils.js');

/**
 * Register a tenant with the API GW.
 * A new tenant is created for each unique namespace/tenantinstance.  If the
 * tenant already exists, the tenant is left as-is
 * Parameters:
 *  gwInfo         - Required. API GW connection information (gwUrl, gwAuth)
 *  namespace      - Required. Namespace of tenant
 *  tenantInstance - Optional. Tenanant instance used to create >1 tenant per namespace
 *                   Defaults to 'openwhisk'
 * Returns:
 *  tenant object  - JSON object representing the tenant in the following format:
 *                   { id: GUID, namespace: NAMESPACE, instance: 'openwhisk' }
 */
function createTenant(gwInfo, namespace, tenantInstance) {
  var instance = tenantInstance || 'openwhisk';  // Default to a fixed instance so all openwhisk tenants have a common instance
  var options = {
    followAllRedirects: true,
    url: gwInfo.gwUrl+'/tenants',
    headers: {
      'Accept': 'application/json'
    },
    json: {                     // Auto set header: 'Content-Type': 'application/json'
      instance: instance,
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
      if (error) console.error('Warning: addTenantToGateway request failed: '+utils2.makeJsonString(error));
      if (body) console.log('addTenantToGateway: response body: '+utils2.makeJsonString(body));

      if (error) {
        console.error('addTenantToGateway: Unable to configure a tenant on the API Gateway');
        reject('Unable to configure the API Gateway: '+utils2.makeJsonString(error));
      } else if (statusCode != 200) {
        if (body) {
          var errMsg = JSON.stringify(body);
          if (body.error && body.error.message) errMsg = body.error.message;
          reject('API Gateway failure (status code '+statusCode+'): '+ errMsg);
        } else {
          reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
        }

      } else {
        if (body && body.id) {  // body has format like:  { id: GUID, namespace: NAMESPACE, instance: 'openwhisk' }
          console.log('addTenantToGateway: got a single tenant response');
          resolve(body);
        } else {
          console.error('addTenantToGateway: failure: No tenant guid provided');
          reject('Unable to configure the API Gateway: Invalid response from API Gateway');
        }
      }
    });
  });
}

/*
 * Return an array of tenants
 */
function getTenants(gwInfo, ns, tenantInstance) {
  var qsNsOnly = { 'filter[where][namespace]' : ns };
  var qsNsAndInstance = { 'filter[where][namespace]' : ns,
                          'filter[where][instance]'  : tenantInstance };
  var qs = qsNsOnly;
  if (tenantInstance) qs = qsNsAndInstance;
  var options = {
    followAllRedirects: true,
    url: gwInfo.gwUrl+'/tenants',
    qs: qs,
    headers: {
      'Accept': 'application/json'
    },
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }
  console.log('getTenants: request: '+JSON.stringify(options));

  return new Promise(function(resolve, reject) {
    request.get(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('getTenants: response status: '+ statusCode);
      if (error) console.error('Warning: getTenant request failed: '+utils2.makeJsonString(error));
      if (body) console.log('getTenants: response body: '+utils2.makeJsonString(body));
      if (error) {
        console.error('getTenants: Unable to obtain tenant from the API Gateway');
        reject('Unable to obtain Tenant from the API Gateway: '+utils2.makeJsonString(error));
      } else if (statusCode != 200) {
        if (body) {
          var errMsg = JSON.stringify(body);
          if (body.error && body.error.message) errMsg = body.error.message;
          reject('API Gateway failure (status code '+statusCode+'): '+ errMsg);
        } else {
          reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
        }
      } else {
        if (body) {
          try {
            var bodyJson = JSON.parse(body);
            if (Array.isArray(bodyJson)) {
              resolve(bodyJson);
            } else {
              console.error('getTenants: Invalid API GW response body; a JSON array was not returned');
              resolve( [] );
            }
          } catch(e) {
            console.error('getTenants: Invalid API GW response body; JSON.parse() failure: '+e);
            reject('Internal error. Invalid API Gateway response: '+e);
          }
        } else {
          console.log('getTenants: No tenants found');
          resolve( [] );
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
 * @param tenantId   Required.
 * @param swaggerApi   Required. The gateway API object to send to the API gateway
 * @param   payload.namespace  Required. The OpenWhisk namespace of the user defining this API route
 * @param   payload.gatewayPath  Required.  The relative path for this route
 * @param   payload.gatewayMethod  Required.  The gateway route REST verb
 * @param   payload.backendUrl  Required.  The full REST URL used to invoke the associated action
 * @param   payload.backendMethod  Required.  The REST verb used to invoke the associated action
 * @return A promise for an object describing the result with fields error and response
 */
function addApiToGateway(gwInfo, tenantId, swaggerApi, gwApiId) {
  var requestFcn = request.post;

  // Init the GW API configuration object; base it off the swagger API
  var gwApi;
  try {
    gwApi = generateGwApiFromSwaggerApi(swaggerApi);
  } catch(e) {
    console.error('generateGwApiFromSwaggerApi exception: '+e);
    return Promise.reject('Invalid API configuration: '+e);
  }
  gwApi.tenantId = tenantId;

  var options = {
    followAllRedirects: true,
    url: gwInfo.gwUrl+'/apis',
    headers: {
      'Accept': 'application/json'
    },
    json: gwApi,  // Use of json automaticatlly sets header: 'Content-Type': 'application/json'
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }

  if (gwApiId) {
    console.log("addApiToGateway: Updating existing API");
    gwApi.id = gwApiId;
    options.url = gwInfo.gwUrl+'/apis/'+gwApiId;
    requestFcn = request.put;
  }

  console.log('addApiToGateway: request: '+JSON.stringify(options, " ", 2));

  return new Promise(function(resolve, reject) {
    requestFcn(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('addApiToGateway: response status:'+ statusCode);
      if (error) console.error('Warning: addRouteToGateway request failed: '+ utils2.makeJsonString(error));
      if (body) console.log('addApiToGateway: response body: '+utils2.makeJsonString(body));

      if (error) {
        console.error('addApiToGateway: Unable to configure the API Gateway');
        reject('Unable to configure the API Gateway: '+utils2.makeJsonString(error));
      } else if (statusCode != 200) {
        if (body) {
          var errMsg = JSON.stringify(body);
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
 * @param gwInfo Required.
 * @param    gwUrl   Required. The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth  Optional. The credentials used to access the API Gateway REST endpoints
 * @param apiId  Required.  Unique Gateway API Id
 * @return A promise for an object describing the result with fields error and response
 */
function deleteApiFromGateway(gwInfo, gwApiId) {
  var options = {
    followAllRedirects: true,
    url: gwInfo.gwUrl+'/apis/'+gwApiId,
    agentOptions: {rejectUnauthorized: false},
    headers: {
      'Accept': 'application/json'
    }
  };
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }
  console.log('deleteApiFromGateway: request: '+JSON.stringify(options, " ", 2));

  return new Promise(function(resolve, reject) {
    request.delete(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('deleteApiFromGateway: response status:'+ statusCode);
      if (error) console.error('Warning: deleteGatewayApi request failed: '+ utils2.makeJsonString(error));
      if (body) console.log('deleteApiFromGateway: response body: '+utils2.makeJsonString(body));

      if (error) {
        console.error('deleteApiFromGateway: Unable to delete the API Gateway');
        reject('Unable to delete the API Gateway: '+utils2.makeJsonString(error));
      } else if (statusCode != 200) {
        if (body) {
          var errMsg = JSON.stringify(body);
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
function getApis(gwInfo, tenantId, bpOrApiName) {
  var qsBasepath = { 'filter[where][basePath]' : bpOrApiName };
  var qsApiName = { 'filter[where][name]' : bpOrApiName };
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
    url: gwInfo.gwUrl+'/tenants/'+tenantId+'/apis',
    headers: {
      'Accept': 'application/json'
    },
  };
  if (qs) {
    options.qs = qs;
  }
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }
  console.log('getApis: request: '+JSON.stringify(options));

  return new Promise(function(resolve, reject) {
    request.get(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('getApis: response status: '+ statusCode);
      if (error) console.error('Warning: getApis request failed: '+utils2.makeJsonString(error));
      if (body) console.log('getApis: response body: '+utils2.makeJsonString(body));
      if (error) {
        console.error('getApis: Unable to obtain API(s) from the API Gateway');
        reject('Unable to obtain API(s) from the API Gateway: '+utils2.makeJsonString(error));
      } else if (statusCode != 200) {
        if (body) {
          var errMsg = JSON.stringify(body);
          if (body.error && body.error.message) errMsg = body.error.message;
          reject('Unable to obtain API(s) from the API Gateway (status code '+statusCode+'): '+ errMsg);
        } else {
          reject('Unable to obtain API(s) from the API Gateway: Response failure code: '+statusCode);
        }
      } else {
        if (body) {
          try {
            var bodyJson = JSON.parse(body);
            if (Array.isArray(bodyJson)) {
              resolve(bodyJson);
            } else {
              console.error('getApis: Invalid API GW response body; a JSON array was not returned');
              resolve( [] );
            }
          } catch(e) {
            console.error('getApis: Invalid API GW response body; JSON.parse() failure: '+e);
            reject('Invalid API Gateway response: '+e);
          }
        } else {
          console.log('getApis: No APIs found');
          resolve( [] );
        }
      }
    });
  });
}

/**
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

/**
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

/**
 * Create a base swagger API object containing the API basepath, but no endpoints
 * Parameters:
 *   basepath   - Required. API basepath
 *   apiname    - Optional. API friendly name. Defaults to basepath
 * Returns:
 *   swaggerApi - API swagger JSON object
 */
function generateBaseSwaggerApi(basepath, apiname) {
  var swaggerApi = {
    swagger: "2.0",
    info: {
      title: apiname || basepath,
      version: "1.0.0"
    },
    basePath: basepath,
    paths: {}
  };
  return swaggerApi;
}

/**
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

/**
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
 * Returns:
 *   swaggerApi - Input JSON object in swagger format containing the union of swaggerApi + new path/operation
 */
function addEndpointToSwaggerApi(swaggerApi, endpoint) {
  var operation = endpoint.gatewayMethod.toLowerCase();
  var auth_base64 = Buffer.from(endpoint.action.authkey,'ascii').toString('base64');

  // If the relative path already exists, append to it; otherwise create it
  if (!swaggerApi.paths[endpoint.gatewayPath]) {
    swaggerApi.paths[endpoint.gatewayPath] = {};
  }
  swaggerApi.paths[endpoint.gatewayPath][operation] = {
    'x-ibm-op-ext': {
      backendMethod: endpoint.action.backendMethod,
      backendUrl: endpoint.action.backendUrl,
      actionName: endpoint.action.name,
      actionNamespace: endpoint.action.namespace,
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

  return swaggerApi;
}

/**
 * Update an existing DB API document by removing the specified relpath/operation section.
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
      return 'No relpath provided; nothing to remove';
  }

  // If an operation is not specified, delete the entire relpath
  if (!operation) {
      console.log('removeEndpointFromSwaggerApi: No operation; removing entire relpath '+relpath);
      if (swaggerApi.paths[relpath]) {
          delete swaggerApi.paths[relpath];
      } else {
          console.log('removeEndpointFromSwaggerApi: relpath '+relpath+' does not exist in the API; already deleted');
          return 'relpath '+relpath+' does not exist in the API';
      }
  } else {
      if (swaggerApi.paths[relpath] && swaggerApi.paths[relpath][operation]) {
          delete swaggerApi.paths[relpath][operation];
          if (Object.keys(swaggerApi.paths[relpath]).length === 0) {
            console.log('removeEndpointFromSwaggerApi: after deleting operation '+operation+', relpath '+relpath+' has no more operations; so deleting entire relpath '+relpath);
            delete swaggerApi.paths[relpath];
          }
      } else {
          console.log('removeEndpointFromSwaggerApi: relpath '+relpath+' with operation '+operation+' does not exist in the API');
          return 'relpath '+relpath+' with operation '+operation+' does not exist in the API';
      }
  }

  return swaggerApi;
}

function confidentialPrint(str) {
    var printStr;
    if (str) {
        printStr = 'XXXXXXXXXX';
    }
    return printStr;
}

/**
 * Create the CLI response payload from an array of GW API objects
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

/**
 * Use the specified GW API object to create an API JSON object in for format the CLI expects.
 * Parameters:
 *  gwApi      - JSON GW API object
 * Returns:
 *  cliApi     - JSON CLI API object
 */
function generateCliApiFromGwApi(gwApi) {
  var cliApi = {};
  cliApi.id = 'Not Used';
  cliApi.key = 'Not Used';
  cliApi.value = {};
  cliApi.value.namespace = 'Not Used';
  cliApi.value.gwApiActivated = true;
  cliApi.value.tenantId = 'Not Used';
  cliApi.value.gwApiUrl = gwApi.managedUrl;
  cliApi.value.apidoc = generateSwaggerApiFromGwApi(gwApi);
  return cliApi;
}

/*
 * Parses the openwhisk action URL and returns the various components
 * Parameters
 *  url    - in format PROTOCOL://HOST/api/v1/namespaces/NAMESPACE/actions/ACTIONNAME
 * Returns
 *  result - an array of strings.
 *           result[0] : Entire URL
 *           result[1] : protocol (i.e. https)
 *           result[2] : host (i.e. myco.com, 1.2.3.4, myco.com/whisk)
 *           result[3] : namespace
 *           result[4] : action name, including the package if used (i.e. myaction, mypkg/myaction)
 */
function parseActionUrl(actionUrl) {
  var actionUrlPattern = /(\w+):\/\/([:\/\w.\-]+)\/api\/v\d\/namespaces\/([@\w .\-]+)\/actions\/([@\w .\-\/]+)/;
  try {
    return actionUrl.match(actionUrlPattern);
  } catch(e) {
    console.error('parseActionUrl: exception: '+e);
    throw 'parseActionUrl: exception: '+e;
  }
}

/*
 * https://172.17.0.1/api/v1/namespaces/whisk.system/actions/getaction
 * would return getaction
 * https://my-host.mycompany.com/api/v1/namespaces/myid@gmail.com_dev/actions/getaction
 * would return getaction
 *
 * https://172.17.0.1/api/v1/namespaces/whisk.system/actions/mypkg/getaction
 * would return mypkg/getaction
 * https://my-host.mycompany.com/api/v1/namespaces/myid@gmail.com_dev/actions/mypkg/getaction
 * would return mypkg/getaction
 */
function getActionNameFromActionUrl(actionUrl) {
  return parseActionUrl(actionUrl)[4];
}

/*
 * https://172.17.0.1/api/v1/namespaces/whisk.system/actions/getaction
 * would return whisk.system
 * https://my-host.mycompany.com/api/v1/namespaces/myid@gmail.com_dev/actions/mypkg/getaction
 * would return myid@gmail.com_dev
 */
function getActionNamespaceFromActionUrl(actionUrl) {
  return parseActionUrl(actionUrl)[3];
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

module.exports.createTenant = createTenant;
module.exports.getTenants = getTenants;
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
