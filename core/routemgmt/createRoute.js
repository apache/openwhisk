/**
 * Create a document in Cloudant database:
 * https://docs.cloudant.com/document.html#documentCreate
 **/

function main(message) {
  var cloudantOrError = getCloudantAccount(message);
  if (typeof cloudantOrError !== 'object') {
    return whisk.error('getCloudantAccount returned an unexpected object type.');
  }
  var cloudant = cloudantOrError;
  var dbname = message.dbname;
  var doc = message.doc;
  var actionname;

  if(!doc) {
    return whisk.error('doc is required.');
  }

  if(!dbname) {
    return whisk.error('dbname is required.');
  }

  if (typeof message.doc === 'object') {
    doc = message.doc;
  } else if (typeof message.doc === 'string') {
    try {
      doc = JSON.parse(message.doc);
    } catch (e) {
      return whisk.error('doc field cannot be parsed. Ensure it is valid JSON.');
    }
  } else {
    return whisk.error('doc field is ' + (typeof doc) + ' and should be an object or a JSON string.');
  }
  var cloudantDb = cloudant.use(dbName);

  if (typeof message.actionname === 'string') {
    actionname = message.actionname;
  } else {
    return whisk.error('actionname must be an action name.');
  }

  insert(cloudantDb, doc, actionname);
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
