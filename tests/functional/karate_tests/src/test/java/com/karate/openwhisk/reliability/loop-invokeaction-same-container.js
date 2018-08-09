function(path2feature,loopcount,nameSpace,Auth){
    var res = [];
    console = {
            log: print,
            warn: print,
            error: print
    };
    for (var i = 0; i <loopcount; i++) {

        if (i==0){
            var res1 = karate.call(path2feature,{params:'?blocking=false&result=true', requestBody:'',actionName : 'helloworld',nameSpace:nameSpace ,Auth:Auth });
            res.push(res1.response);

        }

        else{
            var res1 = karate.call(path2feature,{params:'?blocking=false&result=true', requestBody:'',actionName : 'helloworld',nameSpace:nameSpace ,Auth:Auth });
            res.push(res1.response);
            var actID = res1.activationId;
            java.lang.Thread.sleep(10000);


            var res2 = karate.call('classpath:com/karate/openwhisk/test-same-action-same-container.feature',{ activationId: res1.activationId ,Auth:Auth});
            console.log('I am printing'+i);

        }

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
