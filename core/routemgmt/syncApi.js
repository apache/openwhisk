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
 * Synchronize the API definition across the API Gateway and the API configuration
 * stored in the database.  This action may be needed when failures occur in the middle
 * of processing an API.
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
 *   namespace  Required. The namespace of the API to be synchronized
 *   basepath   Optional. The basepath of the API to be synchronized
 *   reportOnly Optional. Boolean. When true, the actual operations to align the
 *              API Gateway and DB API definitions does not occur.
 * Returns
 *   report     JSON object describing each API's configuration comparison between
 *              the API Gateway and the DB.
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
var request = require('request');
var qs = require('querystring');

var syncActionTypes = {Activate:'Activate', Deactivate: 'Deactivate', UpdateDbApi: 'UpdateDbApi'};
var syncActionStatus = {Pending:'Pending', Complete: 'Complete', Error: 'Error'};

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
  console.log('DB host    : '+confidentialPrint(message.host));
  console.log('DB port    : '+message.port);
  console.log('DB protocol: '+message.protocol);
  console.log('DB username: '+confidentialPrint(message.username));
  console.log('DB database: '+confidentialPrint(message.dbname));
  console.log('GW URL     : '+message.gwUrl);
  console.log('GW User    : '+confidentialPrint(message.gwUser));
  console.log('GW Pwd     : '+confidentialPrint(message.gwPwd));
  console.log('namespace  : '+message.namespace);
  console.log('basepath   : '+message.basepath);
  console.log('reportOnly : '+message.reportOnly);

  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return whisk.error('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(dbname);

  // Synchronize a specified API
  // 1. Get the API's configuration document from the DB
  // 2. Get the API Gateway configuration for the same API
  // 3. Compare the two API configurations; generate a report
  // 4. If !reportOnly, implement synchronization actions listed in report;
  //    update report with success/failure for each action taken
  // 5. Return the report
  var gwApiDoc;
  var dbApiDoc;

  return getDbApiDoc(message.namespace, message.basepath)
  .then(function(dbdoc) {
    dbApiDoc = dbdoc;
    console.log('Got DB API');
    return getGwApiDoc(gwInfo, dbdoc.gwApiGuid, dbdoc.tenantId, dbdoc.apidoc.basePath);
  })
  .then(function(gwApiDoc){
      // GW API results can be API object or null
      return compareGwApiAndDbApi(dbApiDoc, gwApiDoc);
  })
  .then(function(report) {
      console.log('Got sync report');
      if (message.reportOnly) {
        console.log('No synchronization processing; just sending report');
        return Promise.resolve(report);
      }
      console.log('Ready to sync API configuration; actions: ', report.syncActions);
      return syncApi(cloudantDb, report);
  })
  .then(function(finalReport) {
      console.log('Got final sync report; actions: ', finalReport.syncActions);
      return Promise.resolve(finalReport);
  })
  .catch(function(reason) {
      console.error('API synchronization failure: '+reason);
      return Promise.reject('API synchronization failure: '+reason);
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
function getDbApiDoc(namespace, basepath) {
  var actionName = '/whisk.system/routemgmt/getApi';
  var params = {
    'namespace': namespace,
    'basepath': basepath
  }
  console.log('getApiDoc() for namespace:basepath: '+namespace+':'+basepath);
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

/**
 * Configures an API route on the API Gateway.  This API will map to an OpenWhisk action that
 * will be invoked by the API Gateway when the API route is accessed.
 *
 * @param gwInfo Required.
 * @param    gwUrl   Required.  The base URL gateway path (i.e.  'PROTOCOL://gw.host.domain:PORT/CONTEXT')
 * @param    gwAuth  Required.  The credentials used to access the API Gateway REST endpoints
 * @param gwApiDocId Optional.  Required if tenantId/basepath not provided.
 *                              The gateway API GUID.  If null|undefined, use tenantId/basepath for lookup
 * @param tenantId   Optional.  Required if gwApiDocId is null|undefined.  API's tenantId
 * @param basepath   Optional.  Required if gwApiDocId is null|undefined.  API's basepath
 * @return A promise for an object containing either the API object or an error
 */
function getGwApiDoc(gwInfo, gwApiDocId, tenantId, basepath) {
  var options = {
    url: gwInfo.gwUrl+'/apis/'+gwApiDocId,
    headers: {
      'Accept': 'application/json'
    }
  };

  // Query used to obtain GW API when the DB API doc does not have the GW API GUID
  // This might be due to:
  //   - the DB API not having been activated (normal), or
  //   - the DB API was not updated after the GW API was activated, or
  //   - the DB API is corrupted
  var tenantBasepathOptions = {
    url: gwInfo.gwUrl+'/apis',
    qs: {'filter[where][and][0][tenantId]': tenantId, 'filter[where][and][1][basePath]': basepath },
    agentOptions: {rejectUnauthorized: false},
    headers: {
      'Accept': 'application/json'
    }
  };

  if (!gwApiDocId) {
    options = tenantBasepathOptions;
  }
  if (gwInfo.gwAuth) {
    options.headers.Authorization = 'Basic ' + gwInfo.gwAuth;
  }
  console.log('getGwApiDoc: request: '+JSON.stringify(options, " ", 2));

  return new Promise(function(resolve, reject) {
    request.get(options, function(error, response, body) {
      var statusCode = response ? response.statusCode : undefined;
      console.log('getGwApiDoc: response status:'+ statusCode);
      error && console.error('Warning: getGwApiDoc request failed: '+ JSON.stringify(error));
      body && console.log('getGwApiDoc: response body: '+JSON.stringify(body));

      if (error) {
        console.error('getGwApiDoc: Unable to obtain API from the API Gateway: '+JSON.stringify(error))
        reject('Unable obtain API from the API Gateway: '+JSON.stringify(error));
      } else if (statusCode == 404) {
        console.log('getGwApiDoc: No GW API for id: '+gwApiDocId);
        resolve(null);
      } else if (statusCode != 200) {
        console.error('getGwApiDoc: Response code: '+statusCode)
        reject('Unable to configure the API Gateway: Response failure code: '+statusCode);
      } else if (!body) {
        console.error('getGwApiDoc: Unable to configure the API Gateway: No response body')
        reject('Unable to configure the API Gateway: No response received from the API Gateway');
      } else {
        var bodyJson = JSON.parse(body);
        if (bodyJson.length) {
          if (bodyJson.length == 0) {
            console.log('getGwApiDoc: Received zero GW APIs!');
            bodyJson = null;
          } else {
            if (bodyJson.length > 1) {
              console.error('getGwApiDoc: Received >1 GW APIs!  Using first one.');
            }
            bodyJson = bodyJson[0];
          }
        }
        resolve(bodyJson);
      }
    });
  });
}


/*
 * Compare an API's gateway configuration with the database configuration, and
 * generate a comparison report.
 * @param dbApiDoc  Required.  JSON object containing the database's API configuration
 * @param gwApiDoc  Required.  JSON object containing API gateway's API configuration
 */
function compareGwApiAndDbApi(dbApiDoc, gwApiDoc) {
  var report = {};
  report.syncActions = [];
  report.gwApiDocId = dbApiDoc.gwApiGuid;
  report.dbApiDocId = dbApiDoc._id;
  report.gwApiDoc = gwApiDoc;
  report.dbApiDoc = dbApiDoc;
  var gotGwApi = (gwApiDoc.tenantId || (gwApiDoc.length && gwApiDoc.length > 0));
  var gotGwApis = gwApiDoc.length && gwApiDoc.length > 0;
  var action;
  var msg;

  // Check for activation mismatch
  if (dbApiDoc.gwApiActivated == false && gotGwApi) {
    msg = 'DB API is NOT activated, but the GW API is';
    console.log(msg);
    action = {
      mismatch: msg,
      action: syncActionTypes.Deactivate,
      status: syncActionStatus.Pending
    }
    report.syncActions.push(action);
  }
  if (dbApiDoc.gwApiActivated == true  && !gotGwApi) {
    msg = 'DB API is activated, but GW API is NOT';
    console.log(msg);
    action = {
      mismatch: msg,
      action: syncActionTypes.Activate,
      status: syncActionStatus.Pending
    }
    report.syncActions.push(action);
    return Promise.resolve(report);  // No GW API doc, so nothing more to compare
  }

  // If activated, the GW API Guid must be set.  If not set, update the DB API config with the GUID from GW API
  if (dbApiDoc.gwApiActivated == true  && !dbApiDoc.gwApiGuid) {
    msg = 'DB API is activated, but missing the GW API GUID';
    console.log(msg);
    report.gwApiDocId = gwApiDoc.id;
    action = {
      mismatch: msg,
      action: syncActionTypes.UpdateDbApi,
      status: syncActionStatus.Pending,
      updates: {
        gwApiGuid: gwApiDoc.id
      }
    }
    report.syncActions.push(action);
  }

  // If activated, the GW API Url must be set.  If not set, update the DB API config with the Url from GW API
  if (dbApiDoc.gwApiActivated == true  && !dbApiDoc.gwApiUrl) {
    msg = 'DB API is activated, but missing the GW API URL';
    console.log(msg);
    action = {
      mismatch: msg,
      action: syncActionTypes.UpdateDbApi,
      status: syncActionStatus.Pending,
      updates: {
        gwApiUrl: gwApiDoc.managedUrl
      }
    }
    report.syncActions.push(action);
  }

  // Check for metadata mismatches
  if (dbApiDoc.tenantId != gwApiDoc.tenantId) {
        msg = 'Tenant ID mismatch. DB: '+dbApiDoc.tenantId+', GW: '+gwApiDoc.tenantId+'; '+gwApiDoc.basePath+'; '+gwApiDoc.name+'; '+gwApiDoc.id;
        console.log(msg);
        action = {
          mismatch: msg,
          action: syncActionTypes.Activate,
          status: syncActionStatus.Pending
        }
        report.syncActions.push(action)
  }

  if (dbApiDoc.apidoc.basePath != gwApiDoc.basePath) {
        msg = 'Basepath mismatch. DB: '+dbApiDoc.apidoc.basePath+', GW: '+gwApiDoc.basePath;
        console.log(msg);
        action = {
          mismatch: msg,
          action: syncActionTypes.Activate,
          status: syncActionStatus.Pending
        }
        report.syncActions.push(action)
  }

  // Check for missing relative paths and operations
  var dbPathOps = [];
  for (var path in dbApiDoc.apidoc.paths) {
    if (path) {
      for (var operation in dbApiDoc.apidoc.paths[path]) {
        dbPathOps.push(path+':'+operation);
      }
    }
  }
  console.log('DB paths:operations list: ', dbPathOps);
  var gwPathOps = [];
  for (var path in gwApiDoc.resources) {
    if (path && gwApiDoc.resources[path]) {
      for (var operation in gwApiDoc.resources[path].operations) {
        gwPathOps.push(path+':'+operation)
      }
    }
  }
  console.log('GW paths:operations list: ', gwPathOps);
  for (var dbindex in dbPathOps) {
    if (gwPathOps.indexOf(dbPathOps[dbindex]) == -1) {
      msg = 'Path:Operation missing: DB has '+dbPathOps[dbindex]+' but GW does NOT';
      console.log(msg);
      action = {
        mismatch: msg,
        action: syncActionTypes.Activate,
        status: syncActionStatus.Pending
      }
      report.syncActions.push(action)
    }
  }
  for (var gwindex in gwPathOps) {
    if (dbPathOps.indexOf(gwPathOps[gwindex]) == -1) {
      msg = 'Path:Operation missing: GW has '+gwPathOps[gwindex]+' but DB does NOT';
      console.log(msg);
      action = {
        mismatch: msg,
        action: syncActionTypes.Activate,
        status: syncActionStatus.Pending
      }
      report.syncActions.push(action)
    }
  }

  return report;
}

/*
 * Implement the sync actions specified in the sync report
 */
function syncApi(cloudantDb, report) {
  // Currently, the actions are pretty basic.
  // 1. "Activate" == Resend the DB API to the gateway; set DB API status to activated
  // 2. "Deactivate" == Deactivate the API on the gateway; set DP API status to deactivated
  // If both actions are present in the report, Activate first, then Deactivate.  This
  // will make the API on the GW current.
  var activateNeeded = false;
  var deactivateNeeded = false;
  var dbUpdateNeeded = false;

  for (index in report.syncActions) {
    if (report.syncActions[index].action == syncActionTypes.Activate) {
      activateNeeded = true;

    }
    if (report.syncActions[index].action == syncActionTypes.Deactivate) {
      deactivateNeeded = true;
    }
    if (report.syncActions[index].action == syncActionTypes.UpdateDbApi) {
      dbUpdateNeeded = true;
    }
  }
  if (activateNeeded) console.log('syncApi: API activation required');
  if (deactivateNeeded) console.log('syncApi: API deactivation required');
  if (dbUpdateNeeded) console.log('syncApi: DB API configuration update required');

  if (dbUpdateNeeded) {
    dbApiDoc = report.dbApiDoc;
    // Collect all field updates from actions
    // Make updates to DB API object
    for (index in report.syncActions) {
      if (report.syncActions[index].action == syncActionTypes.UpdateDbApi) {
        console.log('syncApi/dbUpdateNeeded: action: ', report.syncActions[index]);
        for (var apiField in report.syncActions[index].updates) {
          console.log('syncApi/dbUpdateNeeded:  DB API doc field: '+apiField+'; value: '+report.syncActions[index].updates[apiField]);
          dbApiDoc[apiField] = report.syncActions[index].updates[apiField];
        }
      }
    }
    // Write updated DB API to DB
    return updateDbApiDoc(cloudantDb, dbApiDoc)
    .then(function() {
      for (index in report.syncActions) {
        if (report.syncActions[index].action == syncActionTypes.UpdateDbApi) {
          report.syncActions[index].status = syncActionStatus.Complete;
        }
      }
      return Promise.resolve(report);
    })
    .catch(function(err) {
      for (index in report.syncActions) {
        if (report.syncActions[index].action == syncActionTypes.UpdateDbApi) {
          report.syncActions[index].status = syncActionStatus.Error;
          report.syncActions[index].error = err;
        }
      }
      return Promise.resolve(report);
    });
  } else if (activateNeeded && deactivateNeeded) {
    return activateApi(report.dbApiDoc.namespace, report.dbApiDoc.apidoc.basePath)
    .then(function() {
      console.log('syncApi: DB API activated');
      for (index in report.syncActions) {
        if (report.syncActions[index].action == syncActionTypes.Activate) {
          report.syncActions[index].status = syncActionStatus.Complete;
        }
      }
      return deactivateApi(report.dbApiDoc.namespace, report.dbApiDoc.apidoc.basePath);
    })
    .then(function() {
      console.log('syncApi: DB API deactivated');
      for (index in report.syncActions) {
        if (report.syncActions[index].action == syncActionTypes.Deactivate) {
          report.syncActions[index].status = syncActionStatus.Complete;
        }
      }
      return Promise.resolve(report);
    });
  } else if (deactivateNeeded && !activateNeeded) {
    return deactivateApi(report.dbApiDoc.namespace, report.dbApiDoc.apidoc.basePath)
    .then(function() {
      console.log('syncApi: DB API deactivated');
      for (index in report.syncActions) {
        if (report.syncActions[index].action == syncActionTypes.Deactivate) {
          report.syncActions[index].status = syncActionStatus.Complete;
        }
      }
      return Promise.resolve(report);
    });
  } else if (activateNeeded) {
    return activateApi(report.dbApiDoc.namespace, report.dbApiDoc.apidoc.basePath)
    .then(function() {
      console.log('syncApi: DB API activated');
      for (index in report.syncActions) {
        if (report.syncActions[index].action == syncActionTypes.Activate) {
          report.syncActions[index].status = syncActionStatus.Complete;
        }
      }
      return Promise.resolve(report);
    });
  }

  console.log('syncApi: No actions to take');
  return Promise.resolve(report);
}


/* Activate the API configuration.  This entails sending the API configuration
 * from the DB to the gateway.
 * This is a wrapper around an action invocation (activateApi)
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

/* Deactivate the API configuration.
 * This is a wrapper around an action invocation (deactivateApi)
 * Parameters
 *   namespace  Required. Openwhisk namespace of this request's originator
 *   basepath   Required if apidoc not provided.  API base path
 */
function deactivateApi(namespace, basepath) {
  var actionName = '/whisk.system/routemgmt/deactivateApi';
  var params = {
    'namespace': namespace,
    'basepath': basepath
  }
  console.log('deactivateApi() params: ', params);
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

/**
 * Update DP API document in database.
 */
function updateDbApiDoc(cloudantDb, doc) {
  return new Promise( function(resolve, reject) {
    cloudantDb.insert(doc, function(error, response) {
      if (!error) {
        console.log("updateDbApiDoc: success", response);
        resolve(response);
      } else {
        console.log("updateDbApiDoc: error", JSON.stringify(error))
        reject(error);
      }
    });
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

  if (!message.namespace) {
    return 'namespace is required.';
  }

  if (!message.basepath) {
    return 'basepath is required.';
  }

  if (message.reportOnly && (typeof message.reportOnly != "boolean")) {
    return 'reportOnly is a boolean having a value of either true or false.';
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

