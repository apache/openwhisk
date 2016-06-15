var fs = require('fs');
var spawn = require('child_process').spawn;

function HelloService(rawLog, logger) {

    var clientProg = "/blackbox/client/clientApp";
    var server = undefined;

    /**
     * Starts the server.
     *
     * @param app express app
     */
    this.start = function start(app) {
        var self = this;
        server = app.listen(app.get('port'), function() {
            var host = server.address().address;
            var port = server.address().port;
            logger.info('[start] listening at http://%s:%s', host, port);
        });
    }

    /**
     * req.body = { main: String, code: String, name: String }
     */
    this.initCode = function initCode(req, res) {
        if (status === Status.ready) {
            try {
                var body = req.body || {};
                var message = body.value || {};
                logger.info('[initCode]', body);

                // Not expected zip which would come from message.lib
                if (message.main && message.code && typeof message.main === 'string' && typeof message.code === 'string') {
                    // .... app-specific initialization ...
                    res.status(200).send();
                }
            } catch (e) {
                logger.error('[initCode]', 'excception', e);
                res.status(500).send();
            }
        } else res.status(409).send();
    }

    /**
     * req.body = { value: Object, meta { activationId : int } }
     */
    this.runCode = function runCode(req, res) {
        var meta = (req.body || {}).meta;
        var value = (req.body || {}).value;
        var payload = value.payload; // we expect this to be a string
        if (typeof payload != 'string')
            payload = JSON.stringify(payload);
        var proc = spawn(clientProg, [ payload ]);
        var output = ''
        proc.stdout.on('data', function(data) {
            // console.log('stdout: ' + data);
            output += data;
        });
        proc.stderr.on('data', function(data) {
            // console.log('stderr: ' + data);
            output += data;
        });
        proc.on('close', function(code) {
            // console.log('child process exited with code ' + code);
            var result = { 'result' : { 'msg' : output } };
            res.status(200).json(result);
        });
    }

}

HelloService.getService = function(rawLog, logger) {
    return new HelloService(rawLog, logger);
}

module.exports = HelloService;
