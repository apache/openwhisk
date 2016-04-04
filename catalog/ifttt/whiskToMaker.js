var request = require('request');

/**
 * Invokes the IFTTT Maker channel with optional three parameters.
 *
 * Must specify the IFTTT maker event and api Key.
 *
 * @param apiKey The IFTTT Maker service API account key.
 * @param event the IFTTT Maker event to invoke.
 * @param value1 optional value to pass to the Maker event.
 * @param value2 optional value to pass to the Maker event.
 * @param value3 optional value to pass to the Maker event.
 */

function main(params) {
    console.log('input params:', params)
    var apiKey = params.apiKey;
    var makerEvent = params.event;
    var makerUrl = 'https://maker.ifttt.com/trigger/'+makerEvent+'/with/key/' + apiKey;
    var jsonData = {};
    
    if(params.hasOwnProperty('value1')){
        jsonData['value1'] = params.value1
    }

    if(params.hasOwnProperty('value2')){
        jsonData['value2'] = params.value2
    }

    if(params.hasOwnProperty('value3')){
        jsonData['value3'] = params.value3
    }

    var options = {
        url: makerUrl,
        method: 'POST',
        body: jsonData,
        json: true,
    };

    //console.log('url:', makerUrl);
    request.post(options, function(error, response, body) {
        if (!error && response.statusCode == 200) {
            console.log(body);
            whisk.done();
        } else {
            console.log('[error]', error, body);
            whisk.error(error);
        }
    });
    
    return whisk.async();
}
