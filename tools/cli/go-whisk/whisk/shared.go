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

package whisk

import "encoding/json"

type KeyValue struct {
    Key  string         `json:"key"`
    Value interface{}   `json:"value"`
}

type KeyValueArr []KeyValue

func (keyValueArr KeyValueArr) GetValue(key string) (interface{}) {
    var res interface{}

    for i := 0; i < len(keyValueArr); i++ {
        if keyValueArr[i].Key == key {
            res = keyValueArr[i].Value
            break;
        }
    }

    Debug(DbgInfo, "Got value '%v' from '%v' for key '%s'\n", res, keyValueArr, key)

    return res
}

type Annotations []map[string]interface{}

type Parameters *json.RawMessage

type Limits struct {
    Timeout *int `json:"timeout,omitempty"`
    Memory  *int `json:"memory,omitempty"`
    Logsize *int `json:"logs,omitempty"`
}
