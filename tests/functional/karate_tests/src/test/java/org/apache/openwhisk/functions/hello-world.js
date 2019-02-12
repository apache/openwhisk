function() {
// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.
var scriptcode ="function main(params) {\n    console.log('hello stdout');\n    console.error('hello stderr');\n    var name = params.name || \"World\";\n    return {payload: \"Hello, \" + name + \"!\"};\n}\n";
return scriptcode;
}
//Sample Hello World Functions

