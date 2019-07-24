/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Minimal conductor action.
 */
function main(args) {
    // propagate errors
    if (args.error) return args
    // unescape params: { action, state, foo, params: { bar } } becomes { action, state, params: { foo, bar } }
    const action = args.action
    const state = args.state
    const params = args.params
    delete args.action
    delete args.state
    delete args.params
    return { action, state, params: Object.assign(args, params) }
}
