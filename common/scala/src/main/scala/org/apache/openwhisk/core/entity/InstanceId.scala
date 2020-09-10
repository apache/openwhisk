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

import spray.json.{deserializationError, DefaultJsonProtocol, JsNumber, JsObject, JsString, JsValue, RootJsonFormat}
import spray.json._

import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * An instance id representing an invoker
 *
 * @param instance a numeric value used for the load balancing and Kafka topic creation
 * @param uniqueName an identifier required for dynamic instance assignment by Zookeeper
 * @param displayedName an identifier that is required for the health protocol to correlate Kafka topics with invoker container names
 */
case class InvokerInstanceId(val instance: Int,
                             uniqueName: Option[String] = None,
                             displayedName: Option[String] = None,
                             val userMemory: ByteSize)
    extends InstanceId {
  def toInt: Int = instance

  override val instanceType = "invoker"

  override val source = s"$instanceType$instance"

  override val toString: String = (Seq("invoker" + instance) ++ uniqueName ++ displayedName).mkString("/")

  override val toJson: JsValue = InvokerInstanceId.serdes.write(this)
}

case class ControllerInstanceId(asString: String) extends InstanceId {
  validate(asString)
  override val instanceType = "controller"

  override val source = s"$instanceType$asString"

  override val toString: String = source

  override val toJson: JsValue = ControllerInstanceId.serdes.write(this)
}

object InvokerInstanceId extends DefaultJsonProtocol {
  def parse(c: String): Try[InvokerInstanceId] = Try(serdes.read(c.parseJson))

  implicit val serdes = new RootJsonFormat[InvokerInstanceId] {
    override def write(i: InvokerInstanceId): JsValue = {
      val fields = new ListBuffer[(String, JsValue)]
      fields ++= List("instance" -> JsNumber(i.instance))
      fields ++= List("userMemory" -> JsString(i.userMemory.toString))
      fields ++= List("instanceType" -> JsString(i.instanceType))
      i.uniqueName.foreach(uniqueName => fields ++= List("uniqueName" -> JsString(uniqueName)))
      i.displayedName.foreach(displayedName => fields ++= List("displayedName" -> JsString(displayedName)))
      JsObject(fields.toSeq: _*)
    }

    override def read(json: JsValue): InvokerInstanceId = {
      val instance = fromField[Int](json, "instance")
      val uniqueName = fromField[Option[String]](json, "uniqueName")
      val displayedName = fromField[Option[String]](json, "displayedName")
      val userMemory = fromField[String](json, "userMemory")
      val instanceType = fromField[String](json, "instanceType")

      if (instanceType == "invoker") {
        new InvokerInstanceId(instance, uniqueName, displayedName, ByteSize.fromString(userMemory))
      } else {
        deserializationError("could not read InvokerInstanceId")
      }
    }
  }

}

object ControllerInstanceId extends DefaultJsonProtocol {
  def parse(c: String): Try[ControllerInstanceId] = Try(serdes.read(c.parseJson))

  implicit val serdes = new RootJsonFormat[ControllerInstanceId] {
    override def write(c: ControllerInstanceId): JsValue =
      JsObject("asString" -> JsString(c.asString), "instanceType" -> JsString(c.instanceType))

    override def read(json: JsValue): ControllerInstanceId = {
      json.asJsObject.getFields("asString", "instanceType") match {
        case Seq(JsString(asString), JsString(instanceType)) =>
          if (instanceType == "controller") {
            new ControllerInstanceId(asString)
          } else {
            deserializationError("could not read ControllerInstanceId")
          }
        case Seq(JsString(asString)) =>
          new ControllerInstanceId(asString)
        case _ =>
          deserializationError("could not read ControllerInstanceId")
      }
    }
  }
}

trait InstanceId {

  // controller ids become part of a kafka topic, hence, hence allow only certain characters
  // see https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/internals/Topic.java#L29
  private val LEGAL_CHARS = "[a-zA-Z0-9._-]+"

  // reserve some number of characters as the prefix to be added to topic names
  private val MAX_NAME_LENGTH = 249 - 121

  def serialize: String = InstanceId.serdes.write(this).compactPrint

  def validate(asString: String): Unit =
    require(
      asString.length <= MAX_NAME_LENGTH && asString.matches(LEGAL_CHARS),
      s"$instanceType instance id contains invalid characters")

  val instanceType: String

  val source: String

  val toJson: JsValue
}

object InstanceId extends DefaultJsonProtocol {
  def parse(i: String): Try[InstanceId] = Try(serdes.read(i.parseJson))

  implicit val serdes = new RootJsonFormat[InstanceId] {
    override def write(i: InstanceId): JsValue = i.toJson

    override def read(json: JsValue): InstanceId = {
      val JsObject(field) = json
      field
        .get("instanceType")
        .map(_.convertTo[String] match {
          case "invoker" =>
            json.convertTo[InvokerInstanceId]
          case "controller" =>
            json.convertTo[ControllerInstanceId]
          case _ =>
            deserializationError("could not read InstanceId")
        })
        .getOrElse(deserializationError("could not read InstanceId"))
    }
  }
}
