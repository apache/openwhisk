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

package commands

import (
    "errors"

    "../wski18n"
    "../../go-whisk/whisk"
)

func entityNameError(entityName string) (error) {
    errMsg := wski18n.T(
        "An entity name, '{{.name}}', was provided instead of a namespace. Valid namespaces are of the following format: /NAMESPACE.",
        map[string]interface{}{
            "name": entityName,
        })

    return whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
}
