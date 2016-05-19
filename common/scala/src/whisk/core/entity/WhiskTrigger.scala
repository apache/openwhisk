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

package whisk.core.entity

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json.DefaultJsonProtocol
import spray.json.JsObject
import whisk.core.database.DocumentFactory
import whisk.core.entity.schema.TriggerRecord
import spray.json.JsString
import spray.json.pimpString

/**
 * WhiskTriggerPut is a restricted WhiskTrigger view that eschews properties
 * that are auto-assigned or derived from URI: namespace and name.
 */
case class WhiskTriggerPut(
    parameters: Option[Parameters] = None,
    limits: Option[TriggerLimits] = None,
    version: Option[SemVer] = None,
    publish: Option[Boolean] = None,
    annotations: Option[Parameters] = None)

/**
 * A WhiskTrigger provides an abstraction of the meta-data
 * for a whisk trigger.
 *
 * The WhiskTrigger object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskTrigger abstraction.
 *
 * @param namespace the namespace for the trigger
 * @param name the name of the trigger
 * @param parameters the set of parameters to bind to the trigger environment
 * @param limits the limits to impose on the trigger
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotation the set of annotations to attribute to the trigger
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskTrigger(
    namespace: Namespace,
    override val name: EntityName,
    parameters: Parameters = Parameters(),
    limits: TriggerLimits = TriggerLimits(),
    version: SemVer = SemVer(),
    publish: Boolean = false,
    annotations: Parameters = Parameters())
    extends WhiskEntity(name) {

    require(limits != null, "limits undefined")

    override def serialize: Try[TriggerRecord] = Try {
        val r = serialize[TriggerRecord](new TriggerRecord)
        r.parameters = parameters.toGson
        r.limits = limits.toGson
        r
    }

    def toJson = WhiskTrigger.serdes.write(this).asJsObject
}

object WhiskTrigger
    extends DocumentFactory[TriggerRecord, WhiskTrigger]
    with WhiskEntityQueries[WhiskTrigger]
    with DefaultJsonProtocol {

    override val collectionName = "triggers"
    override implicit val serdes = jsonFormat7(WhiskTrigger.apply)

    override def apply(r: TriggerRecord): Try[WhiskTrigger] = Try {
        WhiskTrigger(
            Namespace(r.namespace),
            EntityName(r.name),
            Parameters(r.parameters),
            TriggerLimits(r.limits),
            SemVer(r.version),
            r.publish,
            Parameters(r.annotations)).
            revision[WhiskTrigger](r.docinfo.rev)
    }

    override val cacheEnabled = false //disabled for now until redis in place
    override def cacheKeys(w: WhiskTrigger) = Set(w.docid.asDocInfo, w.docinfo)
}

object WhiskTriggerPut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat5(WhiskTriggerPut.apply)
}
