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
    text: params.text,
    icon_emoji: params.icon_emoji
  };

  if (params.attachments) {
    body.attachments = params.attachments;
  }

  console.log(body);
  console.log("to: " + params.url);

  request.post({
    url: params.url,
    formData: {
      payload: JSON.stringify(body)
    }
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
