function(){
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.
var deleteResponse = "function main({name:name='Serverless API'}) {\n    return {\n      body: {payload:`Hello world ${name}`},\n      statusCode: 200,\n      headers:{ 'Content-Type': 'application/json'}\n    };\n}\n";
return deleteResponse;
}
