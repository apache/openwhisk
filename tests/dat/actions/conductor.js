// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

/**
 * Minimal conductor action.
 */
function main(args) {
    // propagate errors
    if (args.error) return args
    // unescape params: { action, state, foo, params: { bar } } becomes { action, state, params: { foo, bar } }
    const action = args.action
    const state = args.state
    const params = args.params
    delete args.action
    delete args.state
    delete args.params
    return { action, state, params: Object.assign(args, params) }
}
