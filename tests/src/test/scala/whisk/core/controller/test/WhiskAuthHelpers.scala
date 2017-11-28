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
package whisk.core.controller.test

import whisk.core.entity.EntityName
import whisk.core.entity.AuthKey
import whisk.core.entity.WhiskNamespace
import whisk.core.entity.WhiskAuth
import whisk.core.entity.Subject
import whisk.core.entitlement.Privilege
import whisk.core.entity.Identity

object WhiskAuthHelpers {
  def newAuth(s: Subject = Subject(), k: AuthKey = AuthKey()) = {
    WhiskAuth(s, Set(WhiskNamespace(EntityName(s.asString), k)))
  }

  def newIdentity(s: Subject = Subject(), k: AuthKey = AuthKey()) = {
    Identity(s, EntityName(s.asString), k, Privilege.ALL)
  }
}
