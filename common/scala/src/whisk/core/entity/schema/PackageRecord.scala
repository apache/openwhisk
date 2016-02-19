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
 * The whisk package representation in the database: it extends EntityRecord with
 * these two properties: parameters, binding The first is an array of key-value
 * pairs [{key: String, value: String}] representing parameter names and values to bind
 * to the environment of an package action before invoking it. The second identifies
 * the originating package if this is a binding with { namespace: Namespace, name: EntityName}
 * otherwise undefined.
 *
 * This class must match the datastore schema in terms of the fields defined
 * and must also define a nullary constructor. The imperative nature is forced
 * upon us due to datastore interaction.
 */
protected[entity] class PackageRecord
    extends EntityRecord
    with EntityParameters {

    /** The originating package if this is a package binding. */
    protected[entity] var binding: JsonObject = null

    override def toString: String =
        s"""|id: ${_id}
            |rev: ${_rev}
            |name: $name
            |version: $version
            |publish: $publish
            |annotations: $annotations
            |binding: $binding
            |parameters: $parameters""".stripMargin.replace("\n", ", ")
}

