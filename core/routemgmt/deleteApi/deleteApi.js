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
 *
 * Delete an API Gateway to action mapping document from the database:
 * https://docs.cloudant.com/document.html#delete
 *
 * Parameters (all as fields in the message JSON object)
 *   gwUrlV2              Required when accesstoken is provided. The V2 API Gateway base path (i.e. http://gw.com)
 *   gwUrl                Required. The API Gateway base path (i.e. http://gw.com)
 *   gwUser               Optional. The API Gateway authentication
 *   gwPwd                Optional. The API Gateway authentication
 *   __ow_user            Optional. Set to the authenticated API authors's namespace when valid authentication is supplied.
 *   namespace            Required if __ow_user not specified.  Namespace of API author
 *   accesstoken          Optional. Dynamic API GW auth.  Overrides gwUser/gwPwd
 *   spaceguid            Optional. Namespace unique id.
 *   tenantInstance       Optional. Instance identifier used when creating the specific API GW Tenant
 *   basepath             Required. Base path or API name of the API
 *   relpath              Optional. Delete just this relative path from the API.  Required if operation is specified
 *   operation            Optional. Delete just this relpath's operation from the API.
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         gwUrl, gwAuth
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
var utils = require('./utils.js');
var utils2 = require('./apigw-utils.js');
var _ = require('lodash');

function main(message) {
  //console.log('message: '+JSON.stringify(message));  // ONLY FOR TEMPORARY/LOCAL DEBUG; DON'T ENABLE PERMANENTLY
  var badArgMsg = validateArgs(message);
  if (badArgMsg) {
    return Promise.reject(utils2.makeErrorResponseObject(badArgMsg, (message.__ow_method != undefined)));
  }

  var gwInfo = {
    gwUrl: message.gwUrl,
  };
  if (message.gwUser && message.gwPwd) {
    gwInfo.gwAuth = Buffer.from(message.gwUser+':'+message.gwPwd,'ascii').toString('base64');
  }

  // Set the User-Agent header value
  if (message.__ow_headers && message.__ow_headers['user-agent']) {
    utils2.setSubUserAgent(message.__ow_headers['user-agent']);
  }

  // Set namespace override if provided
  message.namespace = message.__ow_user || message.namespace;

  var tenantInstance = message.tenantInstance || 'openwhisk';

  // This can be invoked as either a web action or as a normal action
  var calledAsWebAction = message.__ow_method !== undefined;

  // Log parameter values
  console.log('GW URL        : '+message.gwUrl);
  console.log('GW URL V2     : '+message.gwUrlV2);
  console.log('GW User       : '+utils.confidentialPrint(message.gwUser));
  console.log('GW Pwd        : '+utils.confidentialPrint(message.gwPwd));
  console.log('__ow_user     : '+message.__ow_user);
  console.log('namespace     : '+message.namespace);
  console.log('tenantInstance: '+message.tenantInstance+' / '+tenantInstance);
  console.log('accesstoken   : '+message.accesstoken);
  console.log('spaceguid     : '+message.spaceguid);
  console.log('basepath/name : '+message.basepath);
  console.log('relpath       : '+message.relpath);
  console.log('operation     : '+message.operation);
  console.log('calledAsWebAction: '+calledAsWebAction);

  // If no relpath (or relpath/operation) is specified, delete the entire API
  var deleteEntireApi = !message.relpath;

  if (message.accesstoken) {
    // Delete an API route
    // 1. Use the spaceguid and basepath to obtain the API from the API GW
    // 2. If a relpath or relpath/operation is specified (i.e. delete subset of API)
    //    a. Remove that section from the API config
    //    b. Update API GW with updated API config
    // 3. If relpath or replath/operation is NOT specified (i.e. delete entire API)
    //    a. Delete entire API from API GW
    gwInfo.gwUrl = message.gwUrlV2;
    gwInfo.gwAuth = message.accesstoken;

    return utils2.getApis(gwInfo, message.spaceguid, message.basepath)
    .then(function(endpointDocs) {
      console.log('Got '+endpointDocs.length+' APIs');
      if (endpointDocs.length === 0) {
        console.log('No API found for namespace '+message.namespace + ' with basePath '+ message.basepath);
        return Promise.reject('API \''+message.basepath+'\' does not exist.');
      } else if (endpointDocs.length > 1) {
        console.error('Multiple APIs found for namespace '+message.namespace+' with basepath/apiname '+message.basepath);
      }
      return Promise.resolve(endpointDocs[0]);
    })
    .then(function(endpointDoc) {
      console.log('Got API');
      if (deleteEntireApi) {
        console.log('Removing entire API '+message.basepath+' from API GW');
        return utils2.deleteApiFromGateway(gwInfo, message.spaceguid, endpointDoc.artifact_id);
      } else {
        console.log('Removing path '+message.relpath+' with operation '+message.operation+' from API '+message.basepath);
        var endpointToRemove = {
          gatewayMethod: message.operation,
          gatewayPath: message.relpath
        };
        var swaggerOrErrMsg = utils2.removeEndpointFromSwaggerApi(endpointDoc.open_api_doc, endpointToRemove);
        if (typeof swaggerOrErrMsg === 'string' ) {
          return Promise.reject(swaggerOrErrMsg);
        }
        if (_.isEmpty(swaggerOrErrMsg.paths)) {
          console.log('After path/operation removal, no paths exist in API; so removing entire API '+message.basepath+' from API GW');
          return utils2.deleteApiFromGateway(gwInfo, message.spaceguid, endpointDoc.artifact_id);
        }
        return utils2.addApiToGateway(gwInfo, message.spaceguid, swaggerOrErrMsg, endpointDoc.artifact_id);
      }
    })
    .then(function() {
      console.log('deleteApi success');
      return Promise.resolve(utils2.makeResponseObject({}, calledAsWebAction));
    })
    .catch(function(reason) {
        var rejmsg = 'API deletion failure: ' + JSON.parse(utils2.makeJsonString(reason)); // Avoid unnecessary JSON escapes
        console.error(rejmsg);
        return Promise.reject(utils2.makeErrorResponseObject(rejmsg, calledAsWebAction));
    });
  } else {
    // Delete an API route
    // 1. Get the tenant ID associated with the specified namespace and optional tenant instance
    // 2. Obtain the tenantId/basepath/apiName associated API configuration from the API GW
    // 3. If a relpath or relpath/operation is specified (i.e. delete subset of API)
    //    a. Remove that section from the API config
    //    b. Update API GW with updated API config
    // 4. If relpath or replath/operation is NOT specified (i.e. delete entire API)
    //    a. Delete entire API from API GW
    var tenantId;
    return utils.getTenants(gwInfo, message.namespace, tenantInstance)
    .then(function(tenants) {
      // If a non-empty tenant array was returned, pick the first one from the list
      if (tenants.length === 0) {
        console.error('No Tenant found for namespace '+message.namespace);
        return Promise.reject('No Tenant found for namespace '+message.namespace);
      } else if (tenants.length > 1 ) {
        console.error('Multiple tenants found for namespace '+message.namespace+' and tenant instance '+tenantInstance);
        return Promise.reject('Internal error. Multiple API Gateway tenants found for namespace '+message.namespace+' and tenant instance '+tenantInstance);
      }
      console.log('Got a tenant: '+JSON.stringify(tenants[0]));
      tenantId = tenants[0].id;
      return Promise.resolve(tenants[0].id);
    })
    .then(function(tenantId) {
      console.log('Got Tenant ID: '+tenantId);
      return utils.getApis(gwInfo, tenantId, message.basepath);
    })
    .then(function(apis) {
      console.log('Got '+apis.length+' APIs');
      if (apis.length === 0) {
        console.log('No APIs found for namespace '+message.namespace+' with basepath/apiname '+message.basepath);
        return Promise.reject('API \''+message.basepath+'\' does not exist.');
      } else if (apis.length > 1) {
        console.error('Multiple APIs found for namespace '+message.namespace+' with basepath/apiname '+message.basepath);
        Promise.reject('Internal error. Multiple APIs found for namespace '+message.namespace+' with basepath '+message.basepath);
      }
      return Promise.resolve(apis[0]);
    })
    .then(function(gwApi) {
      if (deleteEntireApi) {
        console.log('Removing entire API '+gwApi.basePath+' from API GW');
        return utils.deleteApiFromGateway(gwInfo, gwApi.id);
      } else {
        console.log('Removing path '+message.relpath+'; operation '+message.operation+' from API '+gwApi.basePath);
        var swaggerApi = utils.generateSwaggerApiFromGwApi(gwApi);
        var endpoint = {
          gatewayMethod: message.operation,
          gatewayPath: message.relpath
        };
        var swaggerOrErrMsg = utils.removeEndpointFromSwaggerApi(swaggerApi, endpoint);
        if (typeof swaggerOrErrMsg === 'string' ) {
          return Promise.reject(swaggerOrErrMsg);
        }
        return utils.addApiToGateway(gwInfo, gwApi.tenantId, swaggerOrErrMsg, gwApi.id);
      }
    })
    .then(function() {
      console.log('deleteApi success');
      return Promise.resolve(utils2.makeResponseObject({}, calledAsWebAction));
    })
    .catch(function(reason) {
      var rejmsg = 'API deletion failure: ' + JSON.parse(utils2.makeJsonString(reason)); // Avoid unnecessary JSON escapes
      console.error(rejmsg);
      return Promise.reject(utils2.makeErrorResponseObject(rejmsg, calledAsWebAction));
    });
  }
}


function validateArgs(message) {
  var tmpdoc;
  if(!message) {
    console.error('No message argument!');
    return 'Internal error.  A message parameter was not supplied.';
  }

  if (!message.gwUrl && !message.gwUrlV2) {
    return 'gwUrl is required.';
  }

  if (!message.__ow_user && !message.namespace) {
    return 'Invalid authentication.';
  }

  if (!message.basepath) {
    return 'basepath is required.';
  }

  if (!message.relpath && message.operation) {
    return 'When specifying an operation, the path is required.';
  }

  if (message.operation) {
    message.operation = message.operation.toLowerCase();
  }

  return '';
}

module.exports.main = main;
