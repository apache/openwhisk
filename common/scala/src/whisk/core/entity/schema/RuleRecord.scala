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

import whisk.core.entity.DocId

/**
 * The whisk rule representation in the database: it extends EntityRecord with
 * two properties: trigger and action. Each of these is a name of entity in the
 * same namespace as the rule.
 *
 * This class must match the datastore schema in terms of the fields defined
 * and must also define a nullary constructor. The imperative nature is forced
 * upon us due to datastore interaction.
 */
protected[entity] class RuleRecord extends EntityRecord {

    /** The status of the rule: active, inactive or activating */
    protected[entity] var status: String = null

    /** The trigger name. Trigger belongs to the same namespace as the rule. */
    protected[entity] var trigger: String = null

    /** The action name. Action belongs to the same namespace as the rule. */
    protected[entity] var action: String = null

    override def toString: String =
        s"""|id: ${_id}
            |rev: ${_rev}
            |name: $name
            |version: $version
            |publish: $publish
            |annotations: $annotations
            |status: $status
            |trigger: $trigger
            |action: $action""".stripMargin.replace("\n", ", ")
}
