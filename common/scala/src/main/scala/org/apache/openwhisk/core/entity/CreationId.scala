package org.apache.openwhisk.core.entity

import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.Messages
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.{JsObject, _}

import scala.util.{Failure, Success, Try}

protected[openwhisk] case class CreationId private (val asString: String) extends AnyVal {
  override def toString: String = asString
  def toJsObject: JsObject = JsObject("creationId" -> asString.toJson)
}

protected[core] object CreationId {

  protected[core] trait CreationIdGenerator {
    def make(): CreationId = CreationId.generate()
  }

  /** Checks if the current character is hexadecimal */
  private def isHexadecimal(c: Char) = c.isDigit || c == 'a' || c == 'b' || c == 'c' || c == 'd' || c == 'e' || c == 'f'

  /**
   * Parses an creation id from a string.
   *
   * @param id the creation id as string
   * @return CreationId instance
   */
  def parse(id: String): Try[CreationId] = {
    val length = id.length
    if (length != 32) {
      Failure(new IllegalArgumentException(Messages.creationIdLengthError(SizeError("Creation id", length.B, 32.B))))
    } else if (!id.forall(isHexadecimal)) {
      Failure(new IllegalArgumentException(Messages.creationIdIllegal))
    } else {
      Success(new CreationId(id))
    }
  }

  /**
   * Generates a random creation id using java.util.UUID factory.
   *
   * Uses fast path to generate the CreationId without additional requirement checks.
   *
   * @return new CreationId
   */
  protected[core] def generate(): CreationId = new CreationId(UUIDs.randomUUID().toString.filterNot(_ == '-'))

  protected[core] implicit val serdes: RootJsonFormat[CreationId] = new RootJsonFormat[CreationId] {
    def write(d: CreationId) = JsString(d.toString)

    def read(value: JsValue): CreationId = {
      val parsed = value match {
        case JsString(s) => CreationId.parse(s)
        case JsNumber(n) => CreationId.parse(n.toString)
        case _           => Failure(DeserializationException(Messages.creationIdIllegal))
      }

      parsed match {
        case Success(cid)                         => cid
        case Failure(t: IllegalArgumentException) => deserializationError(t.getMessage)
        case Failure(_)                           => deserializationError(Messages.creationIdIllegal)
      }
    }
  }
}
