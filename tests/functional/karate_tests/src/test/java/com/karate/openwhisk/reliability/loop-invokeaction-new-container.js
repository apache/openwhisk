
function(path2feature,actionName,loopcount,nameSpace,Auth){
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
    var actID2 = res2.activationId;
    actID.push(actID2);

    java.lang.Thread.sleep(10000);


    for (var i = 0; i <actID.length; i++) {
        var res3 = karate.call('classpath:com/karate/openwhisk/reliability/test-same-action-new-container.feature',{ activationId: actID[i] ,Auth:Auth});
        console.log('I am printing'+i+'actid'+actID[i]);


    }
    return actID;
}

/**
 *  Copyright 2017-2018 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Rahul Tripathi
 *
 *
 */
