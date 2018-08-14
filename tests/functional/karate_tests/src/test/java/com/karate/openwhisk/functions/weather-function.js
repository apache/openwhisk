
function(){
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.
var scriptcode="var request = require('request');\n\nfunction main(params) {\n    var location = params.location || 'Vermont';\n    var url = 'https://query.yahooapis.com/v1/public/yql?q=select item.condition from weather.forecast where woeid in (select woeid from geo.places(1) where text=\"' + location + '\")&format=json';\n\n    return new Promise(function(resolve, reject) {\n        request.get(url, function(error, response, body) {\n            if (error) {\n                reject(error);\n            }\n            else {\n                var condition = JSON.parse(body).query.results.channel.item.condition;\n                var text = condition.text;\n                var temperature = condition.temp;\n                var output = 'It is ' + temperature + ' degrees in ' + location + ' and ' + text;\n                resolve({msg: output});\n            }\n        });\n    });\n}\n";
return scriptcode;
}

