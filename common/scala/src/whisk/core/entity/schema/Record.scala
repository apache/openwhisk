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

import whisk.core.entity.DocInfo
import whisk.core.database.Document

/**
 * Base class for datastore documents. A document has an id and a revision property.
 */
protected[entity] trait Record extends Document {

    @throws[IllegalArgumentException]
    override def docinfo: DocInfo = DocInfo ! (_id, _rev)

    protected[entity] def docinfo(d: DocInfo) = {
        if (d != null) {
            _id = d.id()
            _rev = d.rev()
        }
    }
}