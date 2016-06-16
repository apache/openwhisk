/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity

/**
 * A copy of pipe.js code is here as we work on supporting
 * sequencing as a first class entity.  Moving pipe.js
 * into the system and out of the CLI moves us closer to
 * the final abstraction.
 */
protected[core] object Pipecode {

    val code = """
/**
 * Invokes a sequence of actions, piping the output of each to the input of the next.
 *
 * @param _actions An array of action names to invoke.
 * @param <everything else> Passed as input to the first action.
 */
function main(msg) {
    // The actions to invoke sequentially.
    var actions = msg['_actions'];

    if (typeof actions === 'string') {
        try {
            actions = JSON.parse(actions);
        } catch (e) {
            return whisk.error('invalid sequence of actions');
        }
    }

    if (!Array.isArray(actions)) {
        return whisk.error('invalid sequence of actions');
    }

    console.log(actions.length, 'actions to invoke:', actions);

    // The input to the first action.
    var input = msg;
    delete input['_actions'];
    console.log('input to first action:', JSON.stringify(input));
    invokeActions(actions, input, function(result) {
        console.log('chain ending with result', JSON.stringify(result));
        whisk.done(result);
    });

    return whisk.async();
}

/**
 * Invokes a sequence of actions.
 *
 * @param actions Array of action names.
 * @param input Input to the first action.
 * @param terminate Continuation to which the result from the final successful action is passed.
 */
function invokeActions(actions, input, terminate) {
    if (Array.isArray(actions) && actions.length > 0) {
        var params = {
           name: actions[0],
           parameters: input,
           blocking: true,
           next: function(error, activation) {
               if (!error) {
                   console.log('invoke action', actions[0]);
                   console.log('  id:', activation.activationId);
                   console.log('  input:', input);
                   console.log('  result:', activation.result);
                   actions.shift();
                   invokeActions(actions, activation.result, terminate);
               } else {
                   console.log('stopped chain at', actions[0], 'because of an error:', error);
                   whisk.error(error);
               }
           }
        };
        whisk.invoke(params);
    } else terminate(input);
}
"""

}
