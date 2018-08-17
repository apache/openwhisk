function main({name:name='Serverless API'}) {
    return {
      body: {payload:`Hello world ${name}`},
      statusCode: 200,
      headers:{ 'Content-Type': 'application/json'}
    };
}
