function main(args) {
    return {
       "apihost": process.env['__OW_APIHOST'],
       "apikey": process.env['__OW_APIKEY'],
       "namespace": process.env['__OW_NAMESPACE'],
       "action_name": process.env['__OW_ACTION_NAME'],
       "activation_id": process.env['__OW_ACTIVATION_ID'],
       "deadline": process.env['__OW_DEADLINE']
    }
}
