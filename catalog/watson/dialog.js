var watson = require('watson-developer-cloud')
var fs = require('fs')

/**
 * conversation between virtual agents and users
 * see https://www.ibm.com/smarterplanet/us/en/ibmwatson/developercloud/dialog/api/v1/
 * param username The Watson service username.
 * param password The Watson service password.
 * param dialogname The name of the dialog to create.
 * param payload The content of file used to create the dialog.
 * param tempfile The dialog template file used to create the dialog.
 * param inputs The input of the user.
 */
function main(params) {
    var username = params.username;
    var password = params.password;
    var dialogname = params.dialogname || 'my-dialog';
    var tempfile = params.tempfile;
    var payload = params.payload;
    var dialogId = '';
    var inputs = params.inputs;
    var dialog_service = watson.dialog({
        username: username,
        password: password,
        version: 'v1'
    });

    if (!tempfile && !payload) {
        whisk.error('you need to give the payload or tempfile path to create dialog');
    }


    var parameters = {};
    var writeFile = 'temp_file.xml';
    // tempfile is the first choice.
    if (tempfile) {
        parameters = {
            name: dialogname,
            file: fs.createReadStream(tempfile)
        };
    } else {
        //write a temp file.
        var bu = Buffer(payload);
        var writestream = fs.createWriteStream(writeFile);
        writestream.once('open', function() {
            writestream.write(bu);
        });

        writestream.on('close', function() {
        });

        parameters = {
            name: dialogname,
            file: fs.createReadStream(writeFile)
        }
    }

    //create a dialog first
    if (parameters) {
        dialog_service.createDialog(parameters, function(err, dialog) {
            if (err) {
                //Action fails if dialog creation failed.
                console.log(err);
                whisk.error(err);
            } else {
                var dialogId = dialog.dialog_id;
                //start a new conversation if dialog created successfully.
                var params = {
                    dialog_id: dialogId,
                    input: inputs
                };

                dialog_service.conversation(params, function(err, conversation) {
                    if (err) {
                        console.log(err);
                        whisk.error(err);
                    } else {
                        var conversationId = conversation.conversation_id;
                        var clientId = conversation.client_id;
                        var confidence = conversation.condidence;
                        var response = conversation.response[0];
                        console.log('conversationId:', conversationId, ', clientId:', clientId);
                        whisk.done({ conversationId: conversationId, clientId: clientId, confidence: confidence, response: response });
                    }
                });
                //teardown, delete the created dialog, or the watson will response duplicate error
                //if next time create dialog with the same name.
                dialog_service.deleteDialog({ dialog_id: dialogId }, function(err, dialog) {
                    if (err) {
                        console.log(err);
                        whisk.error(err);
                    } else {
                        console.log(dialog);
                    }
                });
            }
        });
    }

}
