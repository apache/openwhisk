var request = require('request');

/**
 * Get hourly weather forecast for a lat/long from the Weather API service.
 *
 * Must specify one of zipCode or latitude/longitude.
 *
 * @param apiKey The Weather service API account key.
 * @param latitude Latitude of coordinate to get forecast.
 * @param longitude Longitude of coordinate to get forecast.
 * @param zipCode ZIP code of desired forecast.
 * @return The hourly forecast for the lat/long.
 */
function main(params) {
    console.log('input params:', params);
    var apiKey = params.apiKey;
    var lat = params.latitude || '0';
    var lon = params.longitude ||  '0';
    var language = params.language || 'en-US';
    var units = params.units || 'm';
    var timePeriod = params.timePeriod || '10day';
    var timeURL ='/forecast/daily/10day';

    switch(timePeriod) {
        case '10day':
            timeURL = '/forecast/daily/10day';
            break;
   	    case '24hour':
            timeURL = '/forecast/hourly/24hour';
            break;
        case 'current':
            timeURL = '/observations/current';
            break;
        case 'timeseries':
            timeURL = '/observations/timeseries/24hour';
            break;
        default:
            timeURL = '/forecast/daily/10day';
            break;
    }

    // Construct url.
    var url = 'https://' + apiKey + '@twcservice.mybluemix.net/api/weather/v2' + timeURL + '?units=' + units + '&geocode=' + lat + '%2C' + lon + '&language=' + language;

    console.log('url:', url);
    request(url, function (error, response, body) {
        if (!error && response.statusCode === 200) {
            var j = JSON.parse(body);
            whisk.done(j);
        } else {
            console.log('error getting forecast');
            console.log('http status code:', response.statusCode);
            console.log('error:', error);
            console.log('body:', body);
            whisk.error(body || error);
        }
    });

    return whisk.async();
}

