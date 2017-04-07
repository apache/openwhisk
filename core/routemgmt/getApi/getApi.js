/**
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
 * Retrieve API configuration from the API Gateway:
 *
 * Parameters (all as fields in the message JSON object)
 *   gwUrlV2              Required when accesstoken is provided. The V2 API Gateway base path (i.e. http://gw.com)
 *   gwUrl                Required. The API Gateway base path (i.e. http://gw.com)
 *   gwUser               Optional. The API Gateway authentication
 *   gwPwd                Optional. The API Gateway authentication
 *   namespace            Required if __ow_user or __ow_meta_namespace not specified.  Namespace of API author
 *   __ow_meta_namespace  Required when accesstoken is not specified. Namespace of API author
 *   __ow_user            Required when accesstoken is specified. Namespace of API author
 *   tenantInstance       Optional. Instance identifier used when creating the specific API GW Tenant
 *   accesstoken          Optional. Dynamic API GW auth.  Overrides gwUser/gwPwd
 *   spaceguid            Optional. Namespace unique id.
 *   basepath             Optional. Base path or API name of the API.
 *                                  If not provided, all APIs for the namespace are returned
 *   relpath              Optional. Must be defined with 'operation'.  Filters API result to path/operation
 *   operation            Optional. Must be defined with 'relpath'.  Filters API result to path/operation
 *   outputFormat         Optional. Defaults to 'swagger'.  Possible values:
 *                                  'apigw' = return API as obtained from the API Gateway
 *                                  'swagger' = return API as swagger compliant JSON
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         gwUrl, gwAuth
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
var request = require('request');
var utils = require('./utils.js');
var utils2 = require('./apigw-utils.js');

function main(message) {
  var badArgMsg = validateArgs(message);
  if (badArgMsg) {
    return Promise.reject(utils2.makeErrorResponseObject(badArgMsg, (message.__ow_method != undefined)));
  }

  message.outputFormat = message.outputFormat || 'swagger';
  var tenantInstance = message.tenantInstance || 'openwhisk';

  var gwInfo = {
    gwUrl: message.gwUrl,
  };
  if (message.gwUser && message.gwPwd) {
    gwInfo.gwAuth = Buffer.from(message.gwUser+':'+message.gwPwd,'ascii').toString('base64');
  }

  // Set namespace override if provided
  message.namespace = (message.accesstoken ? message.__ow_user : message.__ow_meta_namespace) || message.namespace;

  // Log parameter values
  console.log('gwUrl         : '+message.gwUrl);
  console.log('GW URL V2     : '+message.gwUrlV2);
  console.log('__ow_meta_namespace: '+message.__ow_meta_namespace);
  console.log('__ow_user     : '+message.__ow_user);
  console.log('namespace     : '+message.namespace);
  console.log('tenantInstance: '+message.tenantInstance+' / '+tenantInstance);
  console.log('accesstoken   : '+message.accesstoken);
  console.log('spaceguid     : '+message.spaceguid);
  console.log('basepath/name : '+message.basepath);
  console.log('relpath       : '+message.relpath);
  console.log('operation     : '+message.operation);
  console.log('outputFormat  : '+message.outputFormat);

  if (message.accesstoken) {
    gwInfo.gwUrl = message.gwUrlV2;
    gwInfo.gwAuth = message.accesstoken;
    // Obtain the API from the API GW
    return utils2.getApis(gwInfo, message.spaceguid, message.basepath)
    .then(function(endpointDocs) {
      console.log('Got '+endpointDocs.length+' APIs');
      if (endpointDocs.length === 0) {
        console.log('No API found for namespace '+message.namespace + ' with basePath '+ message.basepath)
      }
      var cliApis = utils2.generateCliResponse(endpointDocs);
      console.log('getApi success');
      return Promise.resolve(utils2.makeResponseObject({ apis: cliApis }, (message.__ow_method != undefined)));
    })
    .catch(function(reason) {
      console.error('API GW failure: '+JSON.stringify(reason));
      return Promise.reject(utils2.makeErrorResponseObject(reason, (message.__ow_method != undefined)));
    });
  } else {
    // Issue a request to read API(s) from the API GW
    // 1. Get the tenant ID associated with the specified namespace and optional tenant instance
    // 2. Get the API(s) associated with the tenant ID and optional basepath/apiname
    // 3. Format the API(s) per the outputFormat specification
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
      return Promise.resolve(tenants[0].id);
    })
    .then(function(tenantId) {
      console.log('Got Tenant ID: '+tenantId);
      return utils.getApis(gwInfo, tenantId, message.basepath);
    })
    .then(function(apis) {
      console.log('Got API(s)');
      if (apis.length === 0) {
        console.error('No APIs found for namespace '+message.namespace);
      }
      var cliApis = utils.generateCliResponse(apis);
      console.log('getApi success');
      //MWD return Promise.resolve(utils2.makeResponseObject({ apis: cliApis }, (message.__ow_method != undefined)));
      return Promise.resolve({ apis: cliApis });
    })
    .catch(function(reason) {
      console.error('API GW failure: '+JSON.stringify(reason));
      // Special case handling
      // If no tenant id found, then just return an empty list of APIs
      if ( (typeof reason === 'string') && (reason.indexOf('No Tenant found') !== -1) ) {
        console.log('Namespace has no tenant id yet; returning empty list of APIs');
        return Promise.resolve(utils2.makeResponseObject({ apis: utils.generateCliResponse([]) }, (message.__ow_method != undefined)));
      }
      return Promise.reject(utils2.makeErrorResponseObject(reason, (message.__ow_method != undefined)));
    });
  }

}


function validateArgs(message) {
  if(!message) {
    console.error('No message argument!');
    return 'Internal error. A message parameter was not supplied.';
  }

  if (message.accesstoken && !message.__ow_user) {
    return '__ow_user is required.';
  }

  if (!message.accesstoken && !message.__ow_meta_namespace) {
    return '__ow_meta_namespace is required.';
  }

  if (!message.gwUrl) {
    return 'gwUrl is required.';
  }

  if (message.outputFormat && !(message.outputFormat.toLowerCase() === 'apigw' || message.outputFormat.toLowerCase() === 'swagger')) {
    return 'Invalid outputFormat value. Valid values are: apigw, swagger';
  }

  return '';
}

module.exports.main = main;
