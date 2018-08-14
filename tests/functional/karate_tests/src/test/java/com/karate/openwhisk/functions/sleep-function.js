
function(){
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.
var scriptcode="function main(params) {  return new Promise(function(resolve, reject) {     setTimeout(function() {         resolve({ message: \"Hello world\" });    }, params.time);  });}";
return scriptcode;
}

//Sleep function,Can be used to implement sleep between functions
