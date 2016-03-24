var request = require('request');
/**
 * Gets observation content for a valid TWC location in the supported region or latitude/longitude 
 *
 * Must specify one of zipCode or latitude/longitude.
 *
 * @param apiKey The Weather service API account key.
 * @param latitude Latitude of coordinate to get pollen.
 * @param longitude Longitude of coordinate to get pollen.
 * @param zipCode ZIP code of desired pollen.
 * @return The pollen information for the lat/long.
 */
function main(params) {
    console.log('input params:', params)
    var apiKey = params.apiKey;
    var lat = params.latitude || '0';
    var lon = params.longitude ||  '0';
    var zip = params.zipCode;
    var language = 'en-US';

    // Construct url.
    var url = 'https://api.weather.com/v1';
    if (zip)
        url += '/location/' + zip + ':4:US';
    else
    url += '/geocode/' + lat + '/' + lon;
    url += '/observations/pollen.json'
         + '?language=' + language
         + '&apiKey=' + apiKey;

    console.log('url:', url);
    request(url, function (error, response, body) {
        if (!error && response.statusCode == 200) {
            var j = JSON.parse(body);
            whisk.done(j);
        } else {
            console.log('error getting pollen info');
            console.log('http status code:', response.statusCode);
            console.log('error:', error);
            console.log('body:', body);
            whisk.error(body || error);
        }
    });

    return whisk.async();
}
