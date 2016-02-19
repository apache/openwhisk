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
    console.log('input params:', params)
    var apiKey = params.apiKey;
    var lat = params.latitude || '0';
    var lon = params.longitude ||  '0';
    var zip = params.zipCode;
    var language = 'en-US';
    var units = 'm';

    // Construct url.
    var url = 'https://api.weather.com/v1';
    if (zip)
        url += '/location/' + zip + ':4:US';
    else
        url += '/geocode/' + lat + '/' + lon;
    url += '/forecast/daily/10day.json'
         + '?language=' + language
         + '&apiKey=' + apiKey
         + '&units=' + units;

    console.log('url:', url);
    request(url, function (error, response, body) {
        if (!error && response.statusCode == 200) {
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

