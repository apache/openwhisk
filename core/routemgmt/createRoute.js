/**
 * Create a document in Cloudant database:
 * https://docs.cloudant.com/document.html#documentCreate
 *
 * Parameters (all as fields in the message JSON object)
 *   host       Required. The database dns host name
 *   port       Required. The database port number
 *   protocol   Required. The database protocol (i.e. http, https)
 *   dbname     Required. The name of the database
 *   username   Required. The database user name used to access the database
 *   password   Required. The database user password
 *   apidoc     Required. The API Gateway mapping document
 *
 * NOTE: The package containing this action will be bound to the following values:
 *         host, port, protocol, dbname, username, password
 *       As such, the caller to this action should normally avoid explicitly setting
 *       these values
 **/

function main(message) {
  // The host, port, protocol, username, and password parameters are validated here
  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    return whisk.error('getCloudantAccount returned an unexpected object type.');
  }
  var cloudant = cloudantOrError;
  var doc;
  var dbname;

  // Validate the remaining parameters (apidoc, dbname, and apidoc.action)
  if(!message.apidoc) {
    return whisk.error('apidoc is required.');
  }
  if (typeof message.apidoc === 'object') {
      doc = message.apidoc;
  } else if (typeof message.apidoc === 'string') {
      try {
        doc = JSON.parse(message.apidoc);
      } catch (e) {
        return whisk.error('apidoc field cannot be parsed. Ensure it is valid JSON.');
      }
  } else {
      return whisk.error('apidoc field is ' + (typeof apidoc) + ' and should be an object or a JSON string.');
  }

  if(!message.dbname) {
    return whisk.error('dbname is required.');
  }
  dbname = message.dbname;

  if(!doc.action) {
      return whisk.error('apidoc is missing the fully qualified action name.');
  }
  if (typeof doc.action !== 'string') {
      return whisk.error('action must be an action name.');
  }
  // TODO:  Validate that the action actually exists

  // Log parameter values
  console.log('DB host    : '+message.host);
  console.log('DB port    : '+message.port);
  console.log('DB protocol: '+message.protocol);
  console.log('DB username: '+message.username);
  console.log('DB database: '+message.dbname);
  console.log('action name: '+message.apidoc.action);
  console.log('apidoc     :\n'+JSON.stringify(message.apidoc , null, 2));

  doc.documentTimestamp = (new Date()).toString();

  var cloudantDb = cloudant.use(dbname);
  insert(cloudantDb, doc, doc.action);

  return whisk.async();
}

/**
 * Create document in database.
 */
function insert(cloudantDb, doc, actionname) {
  cloudantDb.insert(doc, actionname, function(error, response) {
    if (!error) {
      console.log("success", response);
      whisk.done(response);
    } else {
      console.log("error", error)
      whisk.error(error);
    }
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
