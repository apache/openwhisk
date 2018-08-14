
function(path2feature,actionName,loopcount,nameSpace,Auth){
 // Licensed to the Apache Software Foundation (ASF) under one or more contributor
 // license agreements; and to You under the Apache License, Version 2.0.
    var res = [];
    var actID=[];
    console = {
            log: print,
            warn: print,
            error: print
    };


    var res1 = karate.call(path2feature,{params:'?blocking=false', requestBody:'{"time":5000}',actionName : actionName,nameSpace:nameSpace ,Auth:Auth });
    res.push(res1.response);
    var actID1 = res1.activationId;
    actID.push(actID1);

    var res2 = karate.call(path2feature,{params:'?blocking=false', requestBody:'{"time":5000}',actionName : actionName,nameSpace:nameSpace ,Auth:Auth });
    res.push(res2.response);
    console.log('I am printing'+i+'actid'+actID[i]);
    var actID2 = res2.activationId;
    actID.push(actID2);

    java.lang.Thread.sleep(10000);


    for (var i = 0; i <actID.length; i++) {
        var res3 = karate.call('classpath:com/karate/openwhisk/reliability/test-same-action-new-container.feature',{ activationId: actID[i] ,Auth:Auth});


    }
    return actID;
}

