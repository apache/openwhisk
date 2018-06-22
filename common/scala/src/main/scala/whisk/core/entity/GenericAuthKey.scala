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

package whisk.core.entity

import akka.http.scaladsl.model.headers.HttpCredentials
import spray.json._

/**
 * Base class for Authentication
 *
 * provides methods to serialize variant forms of authkeys using a JsObject
 */
protected[core] class GenericAuthKey(val toEnvironment: JsObject) {
  def getCredentials: Option[HttpCredentials] = None
}

protected[core] object GenericAuthKey {

  protected[core] implicit val serdes: RootJsonFormat[GenericAuthKey] = new RootJsonFormat[GenericAuthKey] {
    def write(k: GenericAuthKey) = k.toEnvironment
    def read(value: JsValue) = new GenericAuthKey(value.asJsObject)
  }

}
