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
import spray.json.JsString
import spray.json.pimpString
import spray.json.RootJsonFormat

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
 * Representation of a rule to be stored inside a trigger. Contains all
 * information needed to be able to determine if and which action is to
 * be fired.
 *
 * @param action the action to be fired
 * @param status status of the rule
 */
case class ReducedRule(
    action: Namespace,
    status: Status)

/**
 * Trigger as it is returned by the controller. Basically the same as WhiskTrigger,
 * but not including the rule.
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
case class WhiskTriggerResponse(
    namespace: Namespace,
    name: EntityName,
    parameters: Parameters = Parameters(),
    limits: TriggerLimits = TriggerLimits(),
    version: SemVer = SemVer(),
    publish: Boolean = false,
    annotations: Parameters = Parameters()) {

    def toWhiskTrigger = WhiskTrigger(namespace, name, parameters, limits, version, publish, annotations)
}

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
 * @param rules the map of the rules that are associated with this trigger. Key is the rulename and value is the ReducedRule
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
    annotations: Parameters = Parameters(),
    rules: Option[Map[Namespace, ReducedRule]] = Some(Map[Namespace, ReducedRule]()))
    extends WhiskEntity(name) {

    require(limits != null, "limits undefined")

    def toJson = WhiskTrigger.serdes.write(this).asJsObject

    def withoutRules = WhiskTriggerResponse(namespace, name, parameters, limits, version, publish, annotations)

    def addRule(rulename: Namespace, rule: ReducedRule) = {
        val entry = rulename -> rule
        WhiskTrigger(namespace, name, parameters, limits, version, publish, annotations, rules.map(_ + entry)).revision[WhiskTrigger](docinfo.rev)
    }

    def removeRule(rule: Namespace) = {
        WhiskTrigger(namespace, name, parameters, limits, version, publish, annotations, rules.map(_ - rule)).revision[WhiskTrigger](docinfo.rev)
    }
}

object ReducedRule extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat2(ReducedRule.apply)
}

object WhiskTrigger
    extends DocumentFactory[WhiskTrigger]
    with WhiskEntityQueries[WhiskTrigger]
    with DefaultJsonProtocol {

    override val collectionName = "triggers"
    override implicit val serdes = jsonFormat8(WhiskTrigger.apply)

    override val cacheEnabled = false //disabled for now until redis in place
    override def cacheKeys(w: WhiskTrigger) = Set(w.docid.asDocInfo, w.docinfo)
}

object WhiskTriggerPut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat5(WhiskTriggerPut.apply)
}

object WhiskTriggerResponse extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat7(WhiskTriggerResponse.apply)
}
