
function(path2feature,loopcount){
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.
    var res = [];
    console = {
            log: print,
            warn: print,
            error: print
    };
    for (var i = 0; i < loopcount; i++) {

        var res1 = karate.call(path2feature);
        res.push(res1.response);
        var actID = res1.activationId;
        var res2 = karate.call('classpath:com/karate/openwhisk/wsk/actions/get-activation-details.feature',{ activationId: res1.activationId });
        console.log('I am printing'+i)
    }
    return actID;
}
