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

import com.google.gson.JsonObject

/**
 * The whisk action representation in the database: it extends EntityRecord with
 * three properties: parameters, limits and exec. The first is an array of key-value
 * pairs [{key: String, value: String}] representing parameter names and values to bind
 * to the environment of an action before invoking it. The second imposes limits on
 * instances of an action to limit its duration and memory consumption; in the future,
 * it may impose limits across actions. The last is the action to run: either a JS code
 * snippet or a container reference.
 *
 * This class must match the datastore schema in terms of the fields defined
 * and must also define a nullary constructor. The imperative nature is forced
 * upon us due to datastore interaction.
 */
protected[entity] class ActionRecord
    extends EntityRecord
    with EntityParameters
    with EntityLimits {

    /** The action to execute; either a Javascript snippet or a reference to a container image */
    protected[entity] var exec: JsonObject = null

    override def toString: String =
        s"""|id: ${_id}
            |rev: ${_rev}
            |name: $name
            |version: $version
            |publish: $publish
            |annotations: $annotations
            |exec: $exec
            |parameters: $parameters
            |limits: $limits""".stripMargin.replace("\n", ", ")
}

