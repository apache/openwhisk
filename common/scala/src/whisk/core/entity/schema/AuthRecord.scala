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

/**
 * A authentication record. This class must match the datastore schema.
 */
protected[entity] case class AuthRecord protected[entity] (
    protected[entity] val subject: String,
    protected[entity] val uuid: String,
    protected[entity] val key: String)
    extends Record {

    /** Nullary constructor for Gson serdes */
    private def this() = this(null, null, null)

    override def toString: String =
        s"""|id: ${_id}
            |rev: ${_rev}
            |subject: $subject
            |uuid: $uuid
            |key: $key""".stripMargin.replace("\n", ", ")
}
