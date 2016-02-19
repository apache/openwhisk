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

package whisk.core.entity.schema

import whisk.core.entity.TriggerLimits

/**
 * The whisk trigger representation in the database: it extends EntityRecord with
 * two properties: parameters and limits. The first is an array of key-value
 * pairs [{key: String, value: String}] representing parameter names and values
 * to bind to the environment of a trigger before invoking it. The latter imposes
 * limits on instances of a trigger; in the future, may impose limits across trigger
 * types.
 *
 * This class must match the datastore schema in terms of the fields defined
 * and must also define a nullary constructor. The imperative nature is forced
 * upon us due to datastore interaction.
 */
protected[entity] class TriggerRecord
    extends EntityRecord
    with EntityParameters
    with EntityLimits {

    override def toString: String =
        s"""|id: ${_id}
            |rev: ${_rev}
            |name: $name
            |version: $version
            |publish: $publish
            |annotations: $annotations
            |parameters: $parameters
            |limits: $limits""".stripMargin.replace("\n", ", ")
}
