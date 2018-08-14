function main({name:name='Serverless API'}) {
    return {
      body: {payload:`Hello world ${name}`},
      statusCode: 200,
      headers:{ 'Content-Type': 'application/json'}
    };
}
//Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.
