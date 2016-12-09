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
 * Retrieve an API Gateway to action mapping document from the database:
 * https://docs.cloudant.com/document.html#read
 * https://github.com/apache/couchdb-nano#dbgetdocname-params-callback
 *
 * Parameters (all as fields in the message JSON object)
 *   host       Required. The database dns host name
 *   port       Required. The database port number
 *   protocol   Required. The database protocol (i.e. http, https)
 *   dbname     Required. The name of the database
 *   username   Required. The database user name used to access the database
 *   password   Required. The database user password
 *   namespace            Required if __ow_meta_namespace not specified.  Namespace of API author
 *   __ow_meta_namespace  Required if namespace not specified. Namespace of API author
 *   basepath   Optional. Base path or API name of the API.
 *                        If not provided, all APIs for the namespace are returned
 *   relpath    Optional. Must be defined with 'operation'.  Filters API result to path/operation
 *   operation  Optional. Must be defined with 'relpath'.  Filters API result to path/operation
 *   docid      Optional. If specified, this id is used to retrieve the API from the DB; otherwise
 *                        the id is created from the namespace + basepath
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/

function main(message) {
  console.log('getApi:  params: '+JSON.stringify(message));
  var badArgMsg = '';
  if (badArgMsg = validateArgs(message)) {
    return whisk.error(badArgMsg);
  }
  var dbname = message.dbname;

  // Set namespace override if provided
  if (message.__ow_meta_namespace) message.namespace = message.__ow_meta_namespace

  // Log parameter values
  console.log('DB host      : '+message.host);
  console.log('DB port      : '+message.port);
  console.log('DB protocol  : '+message.protocol);
  console.log('DB username  : '+confidentialPrint(message.username));
  console.log('DB password  : '+confidentialPrint(message.password));
  console.log('DB database  : '+message.dbname);
  console.log('namespace    : '+message.namespace);
  console.log('basepath/name: '+message.basepath);
  console.log('relpath      : '+message.relpath);
  console.log('operation    : '+message.operation);
  console.log('docid(param) : '+message.docid);

  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    console.error('CloudantAccount returned an unexpected object type: '+(typeof cloudantOrError));
    return Promise.reject('Internal error.  An unexpected object type was obtained.');
  }
  var cloudant = cloudantOrError;
  var cloudantDb = cloudant.use(message.dbname);

  // Issue a request to read API(s) from the DB
  // 1. If the basepath/apiname was not provided, then obtain all APIs for the specified namespace
  // 2. If an apiname was provided (not a basepath), then return all APIs having that API name (should be just one)
  // 3. If a basepath was provided (not an apiname), then return the API associated with a docid - which was
  //    either a specified parameter value or generated from the namespace+basepath
  var readDbPromise;
  if (!message.basepath) {
    console.log('basepath not provided; getting all APIs for namespace');
    var params = {key: message.namespace};
    readDbPromise = readFilteredApiDocument(cloudantDb, 'gwapis', 'routes-by-namespace', params);
  } else if (message.basepath.indexOf('/') != 0) {
    console.log('basepath prefix is not /, so treating value as the api name');
    var params = {key: [message.namespace, message.basepath]};
    readDbPromise = readFilteredApiDocument(cloudantDb, 'gwapis', 'routes-by-api-name', params);
  } else {
    // NOTE: Couch/Cloudant does not permit a docid to start with "_", and a namespace can be "_"
    var docid = message.docid || "API:"+message.namespace+":"+message.basepath;
    console.log('docid(final) : '+docid);
    readDbPromise = readApiDocument(cloudantDb, docid, {});
  }

  return readDbPromise
  .then(function(dbresults) {
    // The DB read may be been a query view or a specific docid request.  The former returns
    // an array of results; the latter returns a single document.  In all cases return the
    // results as an array - even an array of just one entry.
    if (dbresults.rows) {
      console.log('DB results is an array of '+dbresults.rows.length);
      if (dbresults.rows.length == 0) {
        console.log('DB result entry has 0 rows');
        return { apis: [] };
      }
      return { apis: dbresults.rows };
    } else {
      return { apis: [ {value: dbresults} ] };  // Consistently return an array of results
    }
  })
  .catch(function(reason) {
    console.error('DB failure: '+reason);
    return Promise.reject(reason);
  });
}

function readApiDocument(cloudantDb, docId, params) {
  console.log('readApiDocument: filter params: ', params);
  return new Promise (function(resolve,reject) {
    cloudantDb.get(docId, params, function(error, response) {
      if (!error) {
        console.log('success', JSON.stringify(response));
        resolve(response);
      } else {
        console.error("DB error: ",error.name, error.error, error.reason, error.headers.statusCode);
        if (error.headers.statusCode == 404) {  // When NOT FOUND, return empty results
          resolve({rows: []})
        }
        reject(error);
      }
    });
  });
}

function readFilteredApiDocument(cloudantDb, designDocId, designDocViewName, params) {
  console.log('readFilteredApiDocument: filter params: ', params);
  return new Promise(function (resolve, reject) {
    cloudantDb.view(designDocId, designDocViewName, params, function(error, response) {
      if (!error) {
        console.log('success', response);
        resolve(response);
      } else {
        console.error('error', JSON.stringify(error));
        if (error.headers.statusCode == 404) {  // When NOT FOUND, return empty results
          resolve({rows: []})
        }
        reject(error);
      }
    });
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


function validateArgs(message) {
  var tmpdoc;
  if(!message) {
    console.error('No message argument!');
    return 'Internal error.  A message parameter was not supplied.';
  }

  if (!message.dbname) {
    return 'dbname is required.';
  }

  if(!message.__ow_meta_namespace && !message.namespace) {
    return '__ow_meta_namespace or namespace is required.';
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
