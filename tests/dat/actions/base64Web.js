function main() {
    return {
        headers: {
            "content-type": "application/json"
        },
        statusCode: 200,
        body: new Buffer(JSON.stringify({'status': 'success'})).toString('base64')
    }
}
