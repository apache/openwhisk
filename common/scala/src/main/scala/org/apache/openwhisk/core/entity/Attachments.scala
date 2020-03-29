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

import akka.http.scaladsl.model.ContentType
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.core.entity.size._

import scala.util.Try

object Attachments {

  /**
   * A marker for a field that is either inlined in an entity, or a reference
   * to an attachment. In the case where the value is inlined, it (de)serializes
   * to the same value as if it weren't wrapped.
   *
   * Note that such fields may be defined at any level of nesting in an entity,
   * but the attachments will always be top-level. The logic for actually retrieving
   * an attachment therefore must be separate for all use cases.
   */
  sealed trait Attachment[+T]

  case class Inline[T](value: T) extends Attachment[T]

  case class Attached(attachmentName: String,
                      attachmentType: ContentType,
                      length: Option[Long] = None,
                      digest: Option[String] = None)
      extends Attachment[Nothing]

  // Attachments are considered free because the name/content type are system determined
  // and a size check for the content is done during create/update
  implicit class SizeAttachment[T](a: Attachment[T])(implicit ev: T => SizeConversion) extends SizeConversion {
    def sizeIn(unit: SizeUnits.Unit): ByteSize = a match {
      case Inline(v) => (v: SizeConversion).sizeIn(unit)
      case _         => 0.bytes
    }
  }

  implicit class OptionSizeAttachment[T](a: Option[Attachment[T]])(implicit ev: T => SizeConversion)
      extends SizeConversion {
    def sizeIn(unit: SizeUnits.Unit): ByteSize = a match {
      case Some(Inline(v)) => (v: SizeConversion).sizeIn(unit)
      case _               => 0.bytes
    }
  }

  object Attached {
    implicit val serdes = {
      implicit val contentTypeSerdes = new RootJsonFormat[ContentType] {
        override def write(c: ContentType) = JsString(c.value)

        override def read(js: JsValue): ContentType = {
          Try(js.convertTo[String]).toOption.flatMap(ContentType.parse(_).toOption).getOrElse {
            throw new DeserializationException("Could not deserialize content-type")
          }
        }
      }

      jsonFormat4(Attached.apply)
    }
  }

  implicit def serdes[T: JsonFormat] = new JsonFormat[Attachment[T]] {
    val sub = implicitly[JsonFormat[T]]

    def write(a: Attachment[T]): JsValue = a match {
      case Inline(v)   => sub.write(v)
      case a: Attached => Attached.serdes.write(a)
    }

    def read(js: JsValue): Attachment[T] =
      Try {
        Inline(sub.read(js))
      } recover {
        case _: DeserializationException => Attached.serdes.read(js)
      } getOrElse {
        throw new DeserializationException("Could not deserialize as attachment record: " + js)
      }
  }
}
