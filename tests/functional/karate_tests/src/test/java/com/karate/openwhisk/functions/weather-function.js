
function(){
var scriptcode="var request = require('request');\n\nfunction main(params) {\n    var location = params.location || 'Vermont';\n    var url = 'https://query.yahooapis.com/v1/public/yql?q=select item.condition from weather.forecast where woeid in (select woeid from geo.places(1) where text=\"' + location + '\")&format=json';\n\n    return new Promise(function(resolve, reject) {\n        request.get(url, function(error, response, body) {\n            if (error) {\n                reject(error);\n            }\n            else {\n                var condition = JSON.parse(body).query.results.channel.item.condition;\n                var text = condition.text;\n                var temperature = condition.temp;\n                var output = 'It is ' + temperature + ' degrees in ' + location + ' and ' + text;\n                resolve({msg: output});\n            }\n        });\n    });\n}\n";
return scriptcode;
}

/**
 *  Copyright 2017-2018 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
