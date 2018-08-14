
function(){
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.
var postResponse = "function main(params) {\n    return {\n        statusCode: 200,\n        body: params,\n        headers: {\n            \"Cache-Control\": \"max-age=60\"\n        }\n    }\n}\n";
return postResponse;
}
