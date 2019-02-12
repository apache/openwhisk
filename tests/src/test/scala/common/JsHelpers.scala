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

// This is a copy of the JsHelpers as a trait to allow
// catalog tests to still work until they are migrated
package common

import spray.json.JsObject
import spray.json.JsValue

/**
 * @deprecated Use {@link whisk.common.JsHelpers} instead.
 */
@Deprecated
trait JsHelpers {
  implicit class JsObjectHelper(js: JsObject) {
    def getFieldPath(path: String*): Option[JsValue] = {
      org.apache.openwhisk.utils.JsHelpers.getFieldPath(js, path.toList)
    }

    def fieldPathExists(path: String*): Boolean = {
      org.apache.openwhisk.utils.JsHelpers.fieldPathExists(js, path.toList)
    }
  }
}
