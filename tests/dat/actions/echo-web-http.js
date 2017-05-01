function main(params) {
    var bodyobj = params || {};
    bodystr = JSON.stringify(bodyobj);
    return {
        statusCode: 200,
        headers: { 'Content-Type': 'application/json' },
        body: new Buffer(bodystr).toString('base64')
    };
}

