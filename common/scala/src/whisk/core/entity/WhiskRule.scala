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
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import whisk.core.database.DocumentFactory
import whisk.core.entity.schema.RuleRecord
import whisk.core.entity.schema.Record
import spray.json.JsObject

/**
 * WhiskRulePut is a restricted WhiskRule view that eschews properties
 * that are auto-assigned or derived from URI: namespace, name, status.
 */
case class WhiskRulePut(
    trigger: Option[EntityName] = None,
    action: Option[EntityName] = None,
    version: Option[SemVer] = None,
    publish: Option[Boolean] = None,
    annotations: Option[Parameters] = None)

/**
 * A WhiskRule provides an abstraction of the meta-data for a whisk rule.
 * A rule encodes a trigger to action mapping, such that when a trigger
 * event occurs, the corresponding action is invoked. Both trigger and
 * action are entities in the same namespace as the rule.
 *
 * The WhiskRule object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskRule abstraction.
 *
 * @param namespace the namespace for the rule
 * @param name the name of the rule
 * @param status the status of the rule (one of active, inactive, changing)
 * @param trigger the trigger name to subscribe to
 * @param action the action name to invoke invoke when trigger is fired
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotation the set of annotations to attribute to the rule
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskRule(
    namespace: Namespace,
    override val name: EntityName,
    status: Status,
    trigger: types.Trigger,
    action: types.Action,
    version: SemVer = SemVer(),
    publish: Boolean = false,
    annotations: Parameters = Parameters())
    extends WhiskEntity(name) {

    /**
     * Set rule status: if new status does not match existing status, generate new rule with appropriate status.
     *
     * @param status the rule status, true to indicate rule is enabled and false otherwise
     * @return WhiskRule with status set according to status
     */
    def toggle(newStatus: Status): WhiskRule = {
        WhiskRule(namespace, name, newStatus, trigger, action, version, publish, annotations).
            revision[WhiskRule](docinfo.rev)
    }

    override def serialize: Try[RuleRecord] = Try {
        val r = serialize[RuleRecord](new RuleRecord)
        r.status = status.toString
        r.trigger = trigger()
        r.action = action()
        r
    }

    def toJson = WhiskRule.serdes.write(this).asJsObject
}

/**
 * Status of a rule, recorded in the datastore. It is one of these states:
 * 1) active when the rule is active and ready to respond to triggers
 * 2) inactive when the rule is not active and does not respond to trigger
 * 3) activating when the rule is between status change from inactive to active
 * 4) deactivating when the rule is between status change from active to inactive
 *
 * The "[de]activating" status are used as a mutex in the datastore to exclude
 * other state changes on a rule, and to facilitate synchronous activations
 * and deactivations of a rule.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param status, one of allowed status strings
 */
class Status private (private val status: String) extends AnyVal {
    override def toString = status
}

protected[core] object Status extends ArgNormalizer[Status] {
    val ACTIVE = new Status("active")
    val INACTIVE = new Status("inactive")
    val ACTIVATING = new Status("activating")
    val DEACTIVATING = new Status("deactivating")

    protected[core] def next(status: Status): Status = {
        status match {
            case ACTIVE       => DEACTIVATING
            case DEACTIVATING => INACTIVE
            case INACTIVE     => ACTIVATING
            case ACTIVATING   => ACTIVE
        }
    }

    /**
     * Creates a rule Status from a string.
     *
     * @param str the rule status as string
     * @return Status instance
     * @throws IllegalArgumentException is argument is undefined or not a valid status
     */
    @throws[IllegalArgumentException]
    override protected[entity] def factory(str: String): Status = {
        val status = new Status(str)
        require(status == ACTIVE || status == INACTIVE ||
            status == ACTIVATING || status == DEACTIVATING,
            s"$str is not a recognized rule state")
        status
    }

    override protected[core] implicit val serdes = new RootJsonFormat[Status] {
        def write(s: Status) = JsString(s.status)

        def read(value: JsValue) = Try {
            val JsString(v) = value
            Status(v)
        } match {
            case Success(s) => s
            case Failure(t) => deserializationError(t.getMessage)
        }
    }

    /**
     * A serializer for status POST entities. This is a restricted
     * Status view with only ACTIVE and INACTIVE values allowed.
     */
    protected[core] val serdesRestricted = new RootJsonFormat[Status] {
        def write(s: Status) = JsObject("status" -> JsString(s.status))

        def read(value: JsValue) = Try {
            val JsObject(fields) = value
            val JsString(s) = fields("status")
            Status(s)
        } match {
            case Success(status) =>
                if (status == ACTIVE || status == INACTIVE) {
                    status
                } else {
                    val msg = s"""'$status' is not a recognized rule state, must be one of ['${Status.ACTIVE}', '${Status.INACTIVE}']"""
                    deserializationError(msg)
                }
            case Failure(t) => deserializationError(t.getMessage)
        }
    }
}

object WhiskRule
    extends DocumentFactory[RuleRecord, WhiskRule]
    with WhiskEntityQueries[WhiskRule]
    with DefaultJsonProtocol {

    override val collectionName = "rules"
    override implicit val serdes = jsonFormat8(WhiskRule.apply)

    override def apply(r: RuleRecord): Try[WhiskRule] = Try {
        WhiskRule(
            Namespace(r.namespace),
            EntityName(r.name),
            Status(r.status),
            EntityName(r.trigger),
            EntityName(r.action),
            SemVer(r.version),
            r.publish,
            Parameters(r.annotations)).
            revision[WhiskRule](r.docinfo.rev)
    }

    /**
     * Rules are updated in two components: the controller which locks the record
     * during state changes, and the activator which resolves rule state to the desired
     * state. The controller uses a nanny to confirm the activator completed its job
     * and does an unconditional put on the rule record to reset it to its inactive
     * state when the nanny expires; because the put is unconditional and because a put
     * attempt invalidates the cache (which in turn is only updated if the put succeeds)
     * it is possible to enable caching on the collection but this is not prudent because
     * it will hold up the HTTP response until the nanny has completed, turning all status
     * changes to worse case scenarios.
     */
    override val cacheEnabled = false
    override def cacheKeys(w: WhiskRule) = Set(w.docid.asDocInfo, w.docinfo)
}

object WhiskRulePut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat5(WhiskRulePut.apply)
}
