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

import com.google.gson.JsonArray

/**
 * The most basic whisk representation in the database for parameters on entities such
 * as triggers and actions. This trait add the parameters property.
 *
 * This class must match the datastore schema in terms of the fields defined
 * and must also define a nullary constructor. The imperative nature is forced
 * upon us due to datastore interaction.
 */
protected[entity] trait EntityParameters {
    /** The parameters to bind to the entity environment */
    protected[entity] var parameters: JsonArray = null
}