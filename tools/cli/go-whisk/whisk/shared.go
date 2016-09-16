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

package whisk

import "encoding/json"

type KeyValue struct {
    Key   string        `json:"key,omitempty"`
    Value interface{}   `json:"value"`     // Whisk permits empty values, do not add 'omitempty'
}

type KeyValues struct {
    Key     string `json:"key,omitempty"`
    Values  []string `json:"value,omitempty"`
}

type Annotations []map[string]interface{}

type ActionSequence []KeyValues

//type Parameters []KeyValue
type Parameters *json.RawMessage

type Limits struct {
    Timeout *int `json:"timeout,omitempty"`
    Memory  *int `json:"memory,omitempty"`
    Logsize *int `json:"logs,omitempty"`
}
