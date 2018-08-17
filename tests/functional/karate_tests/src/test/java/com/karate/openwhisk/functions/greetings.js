function() {
    // Licensed to the Apache Software Foundation (ASF) under one or more contributor
    // license agreements; and to You under the Apache License, Version 2.0.
    var scriptcodeWithParam ="function main(params) {\n  console.log('params:', params);\n  var name = params.name || 'stranger';\n  var place = params.place || 'somewhere';\n  return {msg:  'Hello, ' + name + ' from ' + place + '!'};\n}";
    return scriptcodeWithParam;
}
//sample greeting method
