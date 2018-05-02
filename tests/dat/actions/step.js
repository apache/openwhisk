// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

/**
 * Increment action.
 */
function main({ n }) {
    if (typeof n === 'undefined') return { error: 'missing parameter'}
    return { n: n + 1 }
}
