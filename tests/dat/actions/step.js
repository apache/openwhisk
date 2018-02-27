/**
 * Increment action.
 */
function main({ n }) {
    if (typeof n === 'undefined') return { error: 'missing parameter'}
    return { n: n + 1 }
}
