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

import scala.util.Try

import akka.http.scaladsl.model.ContentType

import spray.json._
import spray.json.DefaultJsonProtocol._

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
    sealed trait Attachment[+T] {
        // Similar to Either.fold
        def fold[U](fi: T=>U, fa: =>U) : U = this match {
            case Inline(v) => fi(v)
            case Attached(_, _) => fa
        }
    }

    case class Inline[T](value: T) extends Attachment[T]

    case class Attached(name: String, contentType: ContentType) extends Attachment[Nothing]

    implicit def serdes[T: JsonFormat] = new JsonFormat[Attachment[T]] {
        val sub = implicitly[JsonFormat[T]]

        def write(a: Attachment[T]): JsValue = a match {
            case Inline(v) => sub.write(v)
            case Attached(n, c) => JsObject(
                "attachmentName" -> JsString(n),
                "attachmentType" -> JsString(c.value))
        }

        def read(js: JsValue): Attachment[T] = try {
            Inline(sub.read(js))
        } catch {
            case _: DeserializationException =>
                Try {
                    val o = js.asJsObject
                    val n = o.fields("attachmentName").convertTo[String]
                    val c = o.fields("attachmentType").convertTo[String]
                    val p = ContentType.parse(c).right.get
                    Attached(n, p)
                } getOrElse {
                    throw new DeserializationException("Could not deserialize as attachment record: " + js)
                }
        }
    }
}
