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
 * Deactivate an API from the API Gateway
 *
 * Parameters (all as fields in the message JSON object)
 *   host       Required. The database dns host name
 *   port       Required. The database port number
 *   protocol   Required. The database protocol (i.e. http, https)
 *   dbname     Required. The name of the database
 *   username   Required. The database user name used to access the database
 *   password   Required. The database user password
 *   gwUrl      Required. The API Gateway base path (i.e. http://gw.com)
 *   namespace  Required. The namespace of the API to be activated
 *   basepath   Required. The basepath of the API to be activated

 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/
 var request = require('request');

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
  console.log('DB password: '+confidentialPrint(message.password));
  console.log('DB database: '+message.dbname);
  console.log('GW URL     : '+message.gwUrl);
  console.log('GW User    : '+confidentialPrint(message.gwUser));
  console.log('GW Pwd     : '+confidentialPrint(message.gwPwd));
  console.log('namespace  : '+message.namespace);
  console.log('basepath   : '+message.basepath);

  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return whisk.error('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(dbname);

  // Activating a specified API
  // 1. Pull the API's configuration document from the DB
  // 2. Send the DELETE request to the API Gateway to remove the entire API
  // 3. If successful, update the API configuration in the DB to indicate the activation status
  var dbApiDoc;
  var gwApiDeactivated = false;
  return getDbApiDoc(message.namespace, message.basepath)
  .then(function(dbdoc) {
    dbApiDoc = dbdoc;
    console.log("Gateway doc to deactivate: db id; gw id = "+dbdoc._id+" ; "+dbdoc.gwApiGuid);
    return deleteApiFromGateway(gwInfo, dbdoc.gwApiGuid);
  })
  .then(function() {
    console.log('API '+dbApiDoc._id+' deactivated.');
    gwApiDeactivated = true;
    dbApiDoc.gwApiActivated = false;
    delete dbApiDoc.gwApiGuid;
    return updateApiDocInDb(cloudantDb, dbApiDoc);
  })
  .then(function() {
      console.log('DB updated with API deactivation status');
      return Promise.resolve();
  })
  .catch(function(reason) {
    var msg;
    if (gwApiDeactivated) {
      msg = 'API was deactivated; however, the following DB error occurred: '+reason;
    } else {
      msg = 'API was not deactivated: '+reason;
    }
    console.error(msg);
    return Promise.reject(msg);
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
  console.log('getDbApiDoc() for namespace:basepath: '+namespace+':'+basepath);
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
 * @param apiId  Required.  Unique Gateway API Id
 * @return A promise for an object describing the result with fields error and response
 */
function deleteApiFromGateway(gwInfo, gwApiId) {
  var options = {
    url: gwInfo.gwUrl+'/apis/'+gwApiId,
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
      error && console.error('Warning: deleteApiFromGateway request failed: '+ JSON.stringify(error));
      body && console.log('deleteApiFromGateway: response body: '+JSON.stringify(body));

      if (error) {
        console.error('deleteApiFromGateway: Unable to delete the API Gateway: '+JSON.stringify(error))
        reject('Unable to delete the API Gateway: '+JSON.stringify(error));
      } else if (statusCode != 200) {
        console.error('deleteApiFromGateway: Response code: '+statusCode)
        reject('Unable to delete the API Gateway: Response failure code: '+statusCode);
      } else {
        resolve();
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
    console.log("updateApiDocInDb(): calling insert: "+JSON.stringify(doc))
    cloudantDb.insert(doc, function(error, response) {
      console.log("updateApiDocInDb(): insert() returned")
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
