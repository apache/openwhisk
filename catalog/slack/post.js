var request = require('request');

/**
 *   Action to post to slack
 *  @param {object} params - information about the trigger
 *  @param {string} channel - repository to create webhook
 *  @param {string} username - github username
 *  @param {string} url - slack webhook url
 *  @return {object} whisk async
 */
function main(params) {
  checkParams(params);

  var body = {
    channel: params.channel,
    username: params.username || 'Simple Message Bot',
    text: params.text
  };

  if (params.icon_emoji) {
    // guard against sending icon_emoji: undefined
    body.icon_emoji = params.icon_emoji;
  }

  if (params.token) {
    //
    // this allows us to support /api/chat.postMessage
    // e.g. users can pass params.url = https://slack.com/api/chat.postMessage
    //                 and params.token = <their auth token>
    //
    body.token = params.token;
  } else {
    //
    // the webhook api expects a nested payload
    //
    // notice that we need to stringify; this is due to limitations
    // of the formData npm: it does not handle nested objects
    //
    console.log(body);
    console.log("to: " + params.url);

    body = {
      payload: JSON.stringify(body)
    };
  }

  if (params.attachments) {
    body.attachments = params.attachments;
  }

  request.post({
    url: params.url,
    formData: body
  }, function(err, res, body) {
    if (err) {
      console.log('error: ', err, body);
      whisk.error(err);
    } else {
      console.log('success: ', params.text, 'successfully sent');
      whisk.done();
    }
  });

  return whisk.async();
}

/**
Checks if all required params are set
*/
function checkParams(params) {
  if (params.text === undefined) {
    whisk.error('No text provided');
  }
  if (params.url === undefined) {
    whisk.error('No Webhook URL provided');
  }
  if (params.channel === undefined) {
    whisk.error('No channel provided');
  }
}
