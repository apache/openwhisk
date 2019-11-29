/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.entity

import java.time.Instant

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json.DefaultJsonProtocol
import spray.json.DeserializationException
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import org.apache.openwhisk.core.database.DocumentFactory

/**
 * WhiskRulePut is a restricted WhiskRule view that eschews properties
 * that are auto-assigned or derived from URI: namespace, name, status.
 */
case class WhiskRulePut(trigger: Option[FullyQualifiedEntityName] = None,
                        action: Option[FullyQualifiedEntityName] = None,
                        version: Option[SemVer] = None,
                        publish: Option[Boolean] = None,
                        annotations: Option[Parameters] = None) {

  /**
   * Resolves the trigger and action name if they contains the default namespace.
   */
  protected[core] def resolve(namespace: EntityName): WhiskRulePut = {
    val t = trigger map { _.resolve(namespace) }
    val a = action map { _.resolve(namespace) }
    WhiskRulePut(t, a, version, publish, annotations)
  }
}

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
 * @param trigger the trigger name to subscribe to
 * @param action the action name to invoke invoke when trigger is fired
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotations the set of annotations to attribute to the rule
 * @param updated the timestamp when the rule is updated
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskRule(namespace: EntityPath,
                     override val name: EntityName,
                     trigger: FullyQualifiedEntityName,
                     action: FullyQualifiedEntityName,
                     version: SemVer = SemVer(),
                     publish: Boolean = false,
                     annotations: Parameters = Parameters(),
                     override val updated: Instant = WhiskEntity.currentMillis())
    extends WhiskEntity(name, "rule") {

  def withStatus(s: Status) =
    WhiskRuleResponse(namespace, name, s, trigger, action, version, publish, annotations, updated)

  def toJson = WhiskRule.serdes.write(this).asJsObject
}

/**
 * Rule as it is returned by the controller. Basically the same as WhiskRule,
 * but including the Status which is gotten from the WhiskTrigger the rule
 * refers to.
 *
 * @param namespace the namespace for the rule
 * @param name the name of the rule
 * @param status the status of the rule (one of active, inactive, changing)
 * @param trigger the trigger name to subscribe to
 * @param action the action name to invoke invoke when trigger is fired
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotations the set of annotations to attribute to the rule
 */
case class WhiskRuleResponse(namespace: EntityPath,
                             name: EntityName,
                             status: Status,
                             trigger: FullyQualifiedEntityName,
                             action: FullyQualifiedEntityName,
                             version: SemVer = SemVer(),
                             publish: Boolean = false,
                             annotations: Parameters = Parameters(),
                             updated: Instant) {

  def toWhiskRule = WhiskRule(namespace, name, trigger, action, version, publish, annotations)
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

  protected[core] def next(status: Status): Status = {
    status match {
      case ACTIVE   => INACTIVE
      case INACTIVE => ACTIVE
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
    require(status == ACTIVE || status == INACTIVE, s"$str is not a recognized rule state")
    status
  }

  override protected[core] implicit val serdes = new RootJsonFormat[Status] {
    def write(s: Status) = JsString(s.status)

    def read(value: JsValue) =
      Try {
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

    def read(value: JsValue) =
      Try {
        val JsObject(fields) = value
        val JsString(s) = fields("status")
        Status(s)
      } match {
        case Success(status) =>
          if (status == ACTIVE || status == INACTIVE) {
            status
          } else {
            val msg =
              s"""'$status' is not a recognized rule state, must be one of ['${Status.ACTIVE}', '${Status.INACTIVE}']"""
            deserializationError(msg)
          }
        case Failure(t) => deserializationError(t.getMessage)
      }
  }
}

object WhiskRule extends DocumentFactory[WhiskRule] with WhiskEntityQueries[WhiskRule] with DefaultJsonProtocol {
  import WhiskActivation.instantSerdes

  override val collectionName = "rules"

  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  private val caseClassSerdes = jsonFormat8(WhiskRule.apply)

  override implicit val serdes = new RootJsonFormat[WhiskRule] {
    def write(r: WhiskRule) = caseClassSerdes.write(r)

    def read(value: JsValue) =
      Try {
        caseClassSerdes.read(value)
      } recover {
        case DeserializationException(_, _, List("trigger")) | DeserializationException(_, _, List("action")) =>
          val namespace = value.asJsObject.fields("namespace").convertTo[EntityPath]
          val actionName = value.asJsObject.fields("action")
          val triggerName = value.asJsObject.fields("trigger")

          val refs = Seq(actionName, triggerName).map { name =>
            Try {
              FullyQualifiedEntityName(namespace, EntityName.serdes.read(name))
            } match {
              case Success(n) => n
              case Failure(t) => deserializationError(t.getMessage)
            }
          }
          val fields = value.asJsObject.fields + ("action" -> refs(0).toDocId.toJson) + ("trigger" -> refs(1).toDocId.toJson)
          caseClassSerdes.read(JsObject(fields))
      } match {
        case Success(r) => r
        case Failure(t) => deserializationError(t.getMessage)
      }
  }

  override val cacheEnabled = false
}

object WhiskRuleResponse extends DefaultJsonProtocol {
  import WhiskActivation.instantSerdes
  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  implicit val serdes = jsonFormat9(WhiskRuleResponse.apply)
}

object WhiskRulePut extends DefaultJsonProtocol {
  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  implicit val serdes = jsonFormat5(WhiskRulePut.apply)
}
