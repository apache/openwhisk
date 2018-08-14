
function(raw_swagger){
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.

    var swagger2connv = JSON.stringify(raw_swagger);
    var convertedswagger = '{"apidoc":{"namespace":"guest","swagger":' + swagger2connv + '}}';
    // console.log(convertedswagger);

    return convertedswagger;
}
