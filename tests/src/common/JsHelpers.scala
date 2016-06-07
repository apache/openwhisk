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

package common

import spray.json.JsObject
import spray.json.JsValue

trait JsHelpers {
    implicit class JsObjectHelper(js: JsObject) {
        def getFieldPath(path: String*): Option[JsValue] = {
            if (path.size == 1) {
                Some(js.fields(path(0)))
            } else if (js.getFields(path(0)).size > 0) {
                // current segment exists, but there are more...
                js.fields(path(0)).asJsObject.getFieldPath(path.tail: _*)
            } else {
                None
            }
        }

        def fieldPathExists(path: String*): Boolean = {
            !js.getFieldPath(path: _*).isEmpty
        }
    }
}
