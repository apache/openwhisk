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

import spray.json._

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
 * @param namespace
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

case class Activation(name: String,
                      statusCode: Int,
                      duration: Long,
                      waitTime: Long,
                      initTime: Long,
                      kind: String,
                      conductor: Boolean,
                      memory: Int,
                      causedBy: Option[String])
    extends EventMessageBody {
  val typeName = Activation.typeName
  def serialize = toJson.compactPrint
  def toJson = Activation.activationFormat.write(this)
}

object Activation extends DefaultJsonProtocol {
  val typeName = "Activation"
  def parse(msg: String) = Try(activationFormat.read(msg.parseJson))

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
}

case class Metric(metricName: String, metricValue: Long) extends EventMessageBody {
  val typeName = "Metric"
  def serialize = toJson.compactPrint
  def toJson = Metric.metricFormat.write(this).asJsObject
}

object Metric extends DefaultJsonProtocol {
  def parse(msg: String) = Try(metricFormat.read(msg.parseJson))
  implicit val metricFormat = jsonFormat(Metric.apply _, "metricName", "metricValue")
}
