var request = require('request');

/**
 *   Feed to create a webhook on Github
 *  @param {object} params - information about the trigger
 *  @param {string} repository - repository to create webhook
 *  @param {string} username - github username
 *  @param {string} accessToken - github access token
 *  @param {string} events - list of the events the webhook should fire on
 *  @return {object} whisk async
 */
function main(params) {
  var username = params.username;
  var repository = params.repository;
  var accessToken = params.accessToken;

  var organization,
    repository;

  if(params.repository) {
    var repoSegments = params.repository.split('/');
    if(repoSegments.length == 2) {
      organization = repoSegments[0];
      repository = repoSegments[1];
    } else {
      repository = params.repository;
    }
  }

  var endpoint = 'openwhisk.ng.bluemix.net';
  var lifecycleEvent = params.lifecycleEvent;
  var triggerName = params.triggerName.split("/");

    // URL of the whisk system. The calls of github will go here.
  var whiskCallbackUrl = 'https://' + whisk.getAuthKey() + "@" + endpoint + '/api/v1/namespaces/' + encodeURIComponent(triggerName[1]) + '/triggers/' + encodeURIComponent(triggerName[2]);

    // The URL to create the webhook on Github
  var registrationEndpoint = 'https://api.github.com/repos/' + (organization ? organization : username) + '/' + repository + '/hooks';
  console.log("Using endpoint: " + registrationEndpoint);

  var authorizationHeader = 'Basic ' + new Buffer(username + ':' +
  accessToken).toString('base64');

  if (lifecycleEvent === 'CREATE') {
    var events = params.events.split(',');

    var body = {
      name: 'web',
      active: true,
      events: events,
      config: {
        url: whiskCallbackUrl,
        content_type: 'json'
      }
    };

    var options = {
      method: 'POST',
      url: registrationEndpoint,
      body: JSON.stringify(body),
      headers: {
        'Content-Type': 'application/json',
        'Authorization': authorizationHeader,
        'User-Agent': 'whisk'
      }
    };

    request(options, function(error, response, body) {
      if (error) {
        whisk.error({
          response: response,
          error: error,
          body: body
        });
      } else {
        console.log("Status code: " + response.statusCode);

        if(response.statusCode >= 400) {
          console.log("Response from Github: " + body);
          whisk.error({
            statusCode: response.statusCode,
            response: body
          });
        } else {
          whisk.done({response: body});
        }
      }
    });

    return whisk.async();
  }

  // some lifecycleEvent for which there is nothing to do here
  return whisk.done();
}
