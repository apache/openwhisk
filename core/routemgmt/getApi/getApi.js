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
 * Retrieve API configuration from the API Gateway:
 *
 * Parameters (all as fields in the message JSON object)
 *   gwUrlV2              Required when accesstoken is provided. The V2 API Gateway base path (i.e. http://gw.com)
 *   gwUrl                Required. The API Gateway base path (i.e. http://gw.com)
 *   gwUser               Optional. The API Gateway authentication
 *   gwPwd                Optional. The API Gateway authentication
 *   __ow_user            Optional. Set to the authenticated API authors's namespace when valid authentication is supplied.
 *   namespace            Required if __ow_user not specified.  Namespace of API author
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
var utils = require('./utils.js');
var utils2 = require('./apigw-utils.js');

function main(message) {
  console.log('message: '+JSON.stringify(message));  // ONLY FOR TEMPORARY/LOCAL DEBUG; DON'T ENABLE PERMANENTLY
  var badArgMsg = validateArgs(message);
  if (badArgMsg) {
    return Promise.reject(utils2.makeErrorResponseObject(badArgMsg, (message.__ow_method !== undefined)));
  }

  message.outputFormat = message.outputFormat || 'swagger';
  var tenantInstance = message.tenantInstance || 'openwhisk';

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

  // This can be invoked as either web action or as a normal action
  var calledAsWebAction = message.__ow_method !== undefined;

  // Log parameter values
  console.log('gwUrl         : '+message.gwUrl);
  console.log('GW URL V2     : '+message.gwUrlV2);
  console.log('__ow_user     : '+message.__ow_user);
  console.log('namespace     : '+message.namespace);
  console.log('tenantInstance: '+message.tenantInstance+' / '+tenantInstance);
  console.log('accesstoken   : '+message.accesstoken);
  console.log('spaceguid     : '+message.spaceguid);
  console.log('limit         : '+message.limit);
  console.log('skip          : '+message.skip);
  console.log('basepath/name : '+message.basepath);
  console.log('relpath       : '+message.relpath);
  console.log('operation     : '+message.operation);
  console.log('outputFormat  : '+message.outputFormat);
  console.log('calledAsWebAction: '+calledAsWebAction);

  if (message.accesstoken) {
    gwInfo.gwUrl = message.gwUrlV2;
    gwInfo.gwAuth = message.accesstoken;
    // Obtain the API from the API GW
    return utils2.getApis(gwInfo, message.spaceguid, message.basepath, message.limit, message.skip)
    .then(function(endpointDocs) {
      console.log('Got '+endpointDocs.length+' APIs');
      if (endpointDocs.length === 0) {
        console.log('No API found for namespace '+message.namespace + ' with basePath '+ message.basepath);
      }
      var cliApis = utils2.generateCliResponse(endpointDocs);
      console.log('getApi success');
      return Promise.resolve(utils2.makeResponseObject({ apis: cliApis }, calledAsWebAction));
    })
    .catch(function(reason) {
      var reasonstr = JSON.parse(utils2.makeJsonString(reason)); // Avoid unnecessary JSON escapes
      console.error('API GW failure: '+reasonstr);
      return Promise.reject(utils2.makeErrorResponseObject(reasonstr, calledAsWebAction));
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
      return Promise.resolve(utils2.makeResponseObject({ apis: cliApis }, calledAsWebAction));
    })
    .catch(function(reason) {
      var reasonstr = JSON.parse(utils2.makeJsonString(reason)); // Avoid unnecessary JSON escapes
      var rejmsg = 'API GW failure: ' + reasonstr;
      console.error(rejmsg);
      // Special case handling
      // If no tenant id found, then just return an empty list of APIs
      if ( (typeof reason === 'string') && (reason.indexOf('No Tenant found') !== -1) ) {
        console.log('Namespace has no tenant id yet; returning empty list of APIs');
        return Promise.resolve(utils2.makeResponseObject({ apis: utils.generateCliResponse([]) }, calledAsWebAction));
      }
      return Promise.reject(utils2.makeErrorResponseObject(reasonstr, calledAsWebAction));
    });
  }

}


function validateArgs(message) {
  if(!message) {
    console.error('No message argument!');
    return 'Internal error. A message parameter was not supplied.';
  }

  if (!message.gwUrl && !message.gwUrlV2) {
    return 'gwUrl is required.';
  }

  if (!message.__ow_user && !message.namespace) {
    return 'Invalid authentication.';
  }

  if (message.outputFormat && !(message.outputFormat.toLowerCase() === 'apigw' || message.outputFormat.toLowerCase() === 'swagger')) {
    return 'Invalid outputFormat value. Valid values are: apigw, swagger';
  }

  return '';
}

module.exports.main = main;
