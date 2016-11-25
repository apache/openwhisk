function main(args) {
    return {
       "api_host": process.env['__OW_API_HOST'],
       "api_key": process.env['__OW_API_KEY'],
       "namespace": process.env['__OW_NAMESPACE'],
       "action_name": process.env['__OW_ACTION_NAME'],
       "activation_id": process.env['__OW_ACTIVATION_ID'],
       "deadline": process.env['__OW_DEADLINE']
    }
}
