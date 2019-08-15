/*
Copyright 2018 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
 */

package com.adobe.api.platform.runtime.metrics

import java.util.concurrent.TimeUnit

import spray.json._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

//Classes in this file are taken from OpenWhisk code base

trait EventMessageBody {
  def typeName: String
}

object EventMessageBody extends DefaultJsonProtocol {

  implicit def format = new JsonFormat[EventMessageBody] {
    def write(eventMessageBody: EventMessageBody) = eventMessageBody match {
      case m: Metric     => m.toJson
      case a: Activation => a.toJson
    }

    def read(value: JsValue) =
      if (value.asJsObject.fields.contains("metricName")) {
        value.convertTo[Metric](Metric.metricFormat)
      } else {
        value.convertTo[Activation](Activation.activationFormat)
      }
  }
}

/**
 *
 * @param source Originating source like invoker or controller (with id)
 * @param body Event body which varies based on `eventType`
 * @param subject
 * @param namespace namespace of the user for whom the actual activation flow started. This may not be
 *                  same as the namespace of actual action which got executed.
 * @param userId user uuid
 * @param eventType type of event. Currently 2 `Activation` and `Metric`
 * @param timestamp time when the event is produced
 */
case class EventMessage(source: String,
                        body: EventMessageBody,
                        subject: String,
                        namespace: String,
                        userId: String,
                        eventType: String,
                        timestamp: Long = System.currentTimeMillis()) {
  def serialize = EventMessage.format.write(this).compactPrint
}

object EventMessage extends DefaultJsonProtocol {
  implicit val format =
    jsonFormat(EventMessage.apply _, "source", "body", "subject", "namespace", "userId", "eventType", "timestamp")

  def parse(msg: String) = Try(format.read(msg.parseJson))
}

/**
 *
 * Activation records the actual activation related stats
 *
 * TODO - Have a way to record totalTime for conductors
 *
 * @param name the fully qualified name. It does not include the version
 * @param statusCode activation response status. See `status` method below
 * @param duration actual time the action code was running.
 *                 1. For composition it records the actual user time i.e. duration for which all sub actions executed
 *                 and does not record the actual total time of the whole composition
 * @param waitTime internal system hold time.
 *                 1. For sequences the waitTime is not recorded for intermediate steps. See comment in
 *                 `ContainerProxy#constructWhiskActivation`
 *                 2. For composition - Its zero for top level action
 * @param initTime time it took to initialize an action, e.g. docker init
 *                 1. For composition and sequences - Its zero for top level action
 * @param kind
 * @param conductor true for conductor backed actions
 * @param memory maximum memory allowed for action container
 * @param causedBy contains the "causedBy" annotation (can be "sequence" or nothing at the moment)
 *                 1. For sub actions in a sequence this is set. Its not set for top level activation
 */
case class Activation(name: String,
                      statusCode: Int,
                      duration: Duration,
                      waitTime: Duration,
                      initTime: Duration,
                      kind: String,
                      conductor: Boolean,
                      memory: Int,
                      causedBy: Option[String])
    extends EventMessageBody {
  import Activation._
  val typeName = Activation.typeName
  def serialize = toJson.compactPrint
  def toJson = Activation.activationFormat.write(this)

  def status: String = statusCode match {
    // Defined in ActivationResponse
    case 0 => statusSuccess
    case 1 => statusApplicationError
    case 2 => statusDeveloperError
    case 3 => statusInternalError
    case x => x.toString
  }

  def isColdStart: Boolean = initTime != Duration.Zero
}

object Activation extends DefaultJsonProtocol {
  val typeName = "Activation"
  def parse(msg: String) = Try(activationFormat.read(msg.parseJson))

  val statusSuccess = "success"
  val statusApplicationError = "application_error"
  val statusDeveloperError = "developer_error"
  val statusInternalError = "internal_error"

  private implicit val durationFormat = new RootJsonFormat[Duration] {
    override def write(obj: Duration): JsValue = obj match {
      case o if o.isFinite() => JsNumber(o.toMillis)
      case _                 => JsNumber.zero
    }

    override def read(json: JsValue): Duration = json match {
      case JsNumber(n) if n <= 0 => Duration.Zero
      case JsNumber(n)           => new FiniteDuration(n.longValue(), TimeUnit.MILLISECONDS)
    }
  }

  implicit val activationFormat =
    jsonFormat(
      Activation.apply _,
      "name",
      "statusCode",
      "duration",
      "waitTime",
      "initTime",
      "kind",
      "conductor",
      "memory",
      "causedBy")

  /**
   * Extract namespace and action from name
   * ex. whisk.system/apimgmt/createApi -> (whisk.system, apimgmt/createApi)
   */
  def getNamespaceAndActionName(name: String): (String, String) = {
    val nameArr = name.split("/", 2)
    (nameArr(0), nameArr(1))
  }
}

case class Metric(metricName: String, metricValue: Long) extends EventMessageBody {
  val typeName = Metric.typeName
  def serialize = toJson.compactPrint
  def toJson = Metric.metricFormat.write(this).asJsObject
}

object Metric extends DefaultJsonProtocol {
  val typeName = "Metric"
  def parse(msg: String) = Try(metricFormat.read(msg.parseJson))
  implicit val metricFormat = jsonFormat(Metric.apply _, "metricName", "metricValue")
}
