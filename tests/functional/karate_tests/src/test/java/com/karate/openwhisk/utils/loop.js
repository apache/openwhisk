function(path2feature,loopcount){
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
