/**
 * Print the parameters to the console, sorted alphabetically by key
 */
function main(params) {
    var sep = '';
    var retn = {};
    var keys = [];

    for (var key in params) {
        if (params.hasOwnProperty(key)) {
            keys.push(key);
        }
    }

    keys.sort();
    for (var i in keys) {
        var key = keys[i];
        var value = params[key];
        console.log(sep + 'params.' + key + ':', value);
        sep = ' ';
        retn[key] = value;
    }

    return {params: retn};
}
