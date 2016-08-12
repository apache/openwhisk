var request = require('request');

/**
 * Get hourly weather forecast for a lat/long from the Weather API service.
 *
 * Must specify one of zipCode or latitude/longitude.
 *
 * @param username The Weather service API account username.
 * @param username The Weather service API account password.
 * @param latitude Latitude of coordinate to get forecast.
 * @param longitude Longitude of coordinate to get forecast.
 * @param zipCode ZIP code of desired forecast.
 * @return The hourly forecast for the lat/long.
 */
function main(params) {
    console.log('input params:', params);
    var username = params.username;
    var password = params.password;
    var lat = params.latitude || '0';
    var lon = params.longitude ||  '0';
    var language = params.language || 'en-US';
    var units = params.units || 'm';
    var timePeriod = params.timePeriod || '10day';
    var url = 'https://twcservice.mybluemix.net/api/weather/v1/geocode/' + lat + '/' + lon;
    var qs = {language: language, units: units};

    switch(timePeriod) {
        case '48hour':
            url += '/forecast/hourly/48hour.json';
            break;
        case 'current':
            url += '/observations.json';
            break;
        case 'timeseries':
            url += '/observations/timeseries.json';
            qs.hours = '23';
            break;
        case '10day':
        default:
            url += '/forecast/daily/10day.json';
            break;
    }

    console.log('url:', url);
    request({url:url, qs: qs, auth: {username: username, password: password}, timeout: 30000}, function (error, response, body) {
        if (!error && response.statusCode === 200) {
            var j = JSON.parse(body);
            whisk.done(j);
        } else {
            console.log('error getting forecast');
            console.log('http status code:', (response||{}).statusCode);
            console.log('error:', error);
            console.log('body:', body);
            whisk.error({
               error: error,
               response: response,
               body: body
            });
        }
    });

    return whisk.async();
}
