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

import java.nio.charset.StandardCharsets

import org.apache.openwhisk.core.ConfigKeys

import scala.util.matching.Regex
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.entity.Attachments._
import org.apache.openwhisk.core.entity.ExecManifest._
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.size.SizeString
import pureconfig._
import org.apache.openwhisk.http._

/**
 * Exec encodes the executable details of an action. For black
 * box container, an image name is required. For Javascript and Python
 * actions, the code to execute is required.
 * For Swift actions, the source code to execute the action is required.
 * For Java actions, a base64-encoded string representing a jar file is
 * required, as well as the name of the entrypoint class.
 * An example exec looks like this:
 * { kind  : one of supported language runtimes,
 *   code  : code to execute if kind is supported,
 *   image : container name when kind is "blackbox",
 *   binary: for some runtimes that allow binary attachments,
 *   main  : name of the entry point function, when using a non-default value (for Java, the name of the main class)" }
 */
sealed abstract class Exec extends ByteSizeable {
  override def toString: String = Exec.serdes.write(this).compactPrint

  /** A type descriptor. */
  val kind: String

  /** When true exec may not be executed or updated. */
  val deprecated: Boolean
}

sealed abstract class ExecMetaDataBase extends Exec {
  override def toString: String = ExecMetaDataBase.serdes.write(this).compactPrint
}

/**
 * A common super class for all action exec types that contain their executable
 * code explicitly (i.e., any action other than a sequence).
 */
sealed abstract class CodeExec[+T](implicit ev: T => SizeConversion) extends Exec {

  /** An entrypoint (typically name of 'main' function). 'None' means a default value will be used. */
  val entryPoint: Option[String]

  /** The executable code. */
  val code: T

  /** Serialize code to a JSON value. */
  def codeAsJson: JsValue

  /** The runtime image (either built-in or a public image). */
  val image: ImageName

  /** Indicates if the action execution generates log markers to stdout/stderr once action activation completes. */
  val sentinelledLogs: Boolean

  /** Indicates if a container image is required from the registry to execute the action. */
  val pull: Boolean

  /**
   * Indicates whether the code is stored in a text-readable or binary format.
   * The binary bit may be read from the database but currently it is always computed
   * when the "code" is moved to an attachment this may get changed to avoid recomputing
   * the binary property.
   */
  val binary: Boolean

  override def size = code.sizeInBytes + entryPoint.map(_.sizeInBytes).getOrElse(0.B)
}

sealed abstract class ExecMetaData extends ExecMetaDataBase {

  /** An entrypoint (typically name of 'main' function). 'None' means a default value will be used. */
  val entryPoint: Option[String]

  /** The runtime image (either built-in or a public image). */
  val image: ImageName

  /** Indicates if a container image is required from the registry to execute the action. */
  val pull: Boolean

  /**
   * Indicates whether the code is stored in a text-readable or binary format.
   * The binary bit may be read from the database but currently it is always computed
   * when the "code" is moved to an attachment this may get changed to avoid recomputing
   * the binary property.
   */
  val binary: Boolean

  override def size = 0.B
}

trait AttachedCode {
  def inline(bytes: Array[Byte]): Exec
  def attach(attached: Attached): Exec
}

protected[core] case class CodeExecAsString(manifest: RuntimeManifest,
                                            override val code: String,
                                            override val entryPoint: Option[String])
    extends CodeExec[String] {
  override val kind = manifest.kind
  override val image = manifest.image
  override val sentinelledLogs = manifest.sentinelledLogs.getOrElse(true)
  override val deprecated = manifest.deprecated.getOrElse(false)
  override val pull = false
  override lazy val binary = Exec.isBinaryCode(code)
  override def codeAsJson = JsString(code)
}

protected[core] case class CodeExecMetaDataAsString(manifest: RuntimeManifest,
                                                    override val binary: Boolean = false,
                                                    override val entryPoint: Option[String])
    extends ExecMetaData {
  override val kind = manifest.kind
  override val image = manifest.image
  override val deprecated = manifest.deprecated.getOrElse(false)
  override val pull = false
}

protected[core] case class CodeExecAsAttachment(manifest: RuntimeManifest,
                                                override val code: Attachment[String],
                                                override val entryPoint: Option[String],
                                                override val binary: Boolean = false)
    extends CodeExec[Attachment[String]]
    with AttachedCode {
  override val kind = manifest.kind
  override val image = manifest.image
  override val sentinelledLogs = manifest.sentinelledLogs.getOrElse(true)
  override val deprecated = manifest.deprecated.getOrElse(false)
  override val pull = false
  override def codeAsJson = code.toJson

  override def inline(bytes: Array[Byte]): CodeExecAsAttachment = {
    val encoded = new String(bytes, StandardCharsets.UTF_8)
    copy(code = Inline(encoded))
  }

  override def attach(attached: Attached): CodeExecAsAttachment = {
    copy(code = attached)
  }
}

protected[core] case class CodeExecMetaDataAsAttachment(manifest: RuntimeManifest,
                                                        override val binary: Boolean = false,
                                                        override val entryPoint: Option[String])
    extends ExecMetaData {
  override val kind = manifest.kind
  override val image = manifest.image
  override val deprecated = manifest.deprecated.getOrElse(false)
  override val pull = false
}

/**
 * @param image the image name
 * @param code an optional script or zip archive (as base64 encoded) string
 */
protected[core] case class BlackBoxExec(override val image: ImageName,
                                        override val code: Option[Attachment[String]],
                                        override val entryPoint: Option[String],
                                        val native: Boolean,
                                        override val binary: Boolean)
    extends CodeExec[Option[Attachment[String]]]
    with AttachedCode {
  override val kind = Exec.BLACKBOX
  override val deprecated = false
  override def codeAsJson = code.toJson
  override val sentinelledLogs = native
  override val pull = !native
  override def size = super.size + image.resolveImageName().sizeInBytes

  override def inline(bytes: Array[Byte]): BlackBoxExec = {
    val encoded = new String(bytes, StandardCharsets.UTF_8)
    copy(code = Some(Inline(encoded)))
  }

  override def attach(attached: Attached): BlackBoxExec = {
    copy(code = Some(attached))
  }
}

protected[core] case class BlackBoxExecMetaData(override val image: ImageName,
                                                override val entryPoint: Option[String],
                                                val native: Boolean,
                                                override val binary: Boolean = false)
    extends ExecMetaData {
  override val kind = ExecMetaDataBase.BLACKBOX
  override val deprecated = false
  override val pull = !native
}

protected[core] case class SequenceExec(components: Vector[FullyQualifiedEntityName]) extends Exec {
  override val kind = Exec.SEQUENCE
  override val deprecated = false
  override def size = components.map(_.size).reduceOption(_ + _).getOrElse(0.B)
}

protected[core] case class SequenceExecMetaData(components: Vector[FullyQualifiedEntityName]) extends ExecMetaDataBase {
  override val kind = ExecMetaDataBase.SEQUENCE
  override val deprecated = false
  override def size = components.map(_.size).reduceOption(_ + _).getOrElse(0.B)
}

object Exec extends ArgNormalizer[Exec] with DefaultJsonProtocol {

  val maxSize: ByteSize = 48.MB
  val sizeLimit = loadConfigOrThrow[ByteSize](ConfigKeys.execSizeLimit)

  require(
    sizeLimit <= maxSize,
    s"Executable code size limit $sizeLimit specified by '${ConfigKeys.execSizeLimit}' should not be more than max size of $maxSize")

  // The possible values of the JSON 'kind' field for certain runtimes:
  // - Sequence because it is an intrinsic
  // - Black Box because it is a type marker
  protected[core] val SEQUENCE = "sequence"
  protected[core] val BLACKBOX = "blackbox"

  // This is for error cases where the action `kind` may not be known.
  protected[core] val UNKNOWN = "unknown"

  private def execManifests = ExecManifest.runtimesManifest

  override protected[core] implicit lazy val serdes = new RootJsonFormat[Exec] {
    private def attFmt[T: JsonFormat] = Attachments.serdes[T]
    private lazy val runtimes: Set[String] = execManifests.knownContainerRuntimes ++ Set(SEQUENCE, BLACKBOX)

    override def write(e: Exec) = e match {
      case c: CodeExecAsString =>
        val base = Map("kind" -> JsString(c.kind), "code" -> JsString(c.code), "binary" -> JsBoolean(c.binary))
        val main = c.entryPoint.map("main" -> JsString(_))
        JsObject(base ++ main)

      case a: CodeExecAsAttachment =>
        val base =
          Map("kind" -> JsString(a.kind), "code" -> attFmt[String].write(a.code), "binary" -> JsBoolean(a.binary))
        val main = a.entryPoint.map("main" -> JsString(_))
        JsObject(base ++ main)

      case s @ SequenceExec(comp) =>
        JsObject("kind" -> JsString(s.kind), "components" -> comp.map(_.qualifiedNameWithLeadingSlash).toJson)

      case b: BlackBoxExec =>
        val base =
          Map(
            "kind" -> JsString(b.kind),
            "image" -> JsString(b.image.resolveImageName()),
            "binary" -> JsBoolean(b.binary))
        val code = b.code.map("code" -> attFmt[String].write(_))
        val main = b.entryPoint.map("main" -> JsString(_))
        JsObject(base ++ code ++ main)
      case _ => JsObject.empty
    }

    override def read(v: JsValue) = {
      require(v != null)

      val obj = v.asJsObject

      val kind = obj.fields.get("kind") match {
        case Some(JsString(k)) => k.trim.toLowerCase
        case _                 => throw new DeserializationException("'kind' must be a string defined in 'exec'")
      }

      lazy val optMainField: Option[String] = obj.fields.get("main") match {
        case Some(JsString(m)) => Some(m)
        case Some(_) =>
          throw new DeserializationException(s"if defined, 'main' be a string in 'exec' for '$kind' actions")
        case None => None
      }

      kind match {
        case Exec.SEQUENCE =>
          val comp: Vector[FullyQualifiedEntityName] = obj.fields.get("components") match {
            case Some(JsArray(components)) => components map (FullyQualifiedEntityName.serdes.read(_))
            case Some(_)                   => throw new DeserializationException(s"'components' must be an array")
            case None                      => throw new DeserializationException(s"'components' must be defined for sequence kind")
          }
          SequenceExec(comp)

        case Exec.BLACKBOX =>
          val image: ImageName = obj.fields.get("image") match {
            case Some(JsString(i)) => ImageName.fromString(i).get // throws deserialization exception on failure
            case _ =>
              throw new DeserializationException(
                s"'image' must be a string defined in 'exec' for '${Exec.BLACKBOX}' actions")
          }
          val (codeOpt: Option[Attachment[String]], binary) = obj.fields.get("code") match {
            case None                                => (None, false)
            case Some(JsString(i)) if i.trim.isEmpty => (None, false)
            case Some(code)                          => (Some(attFmt[String].read(code)), isBinary(code, obj))
          }
          val native = execManifests.skipDockerPull(image)
          BlackBoxExec(image, codeOpt, optMainField, native, binary)

        case _ =>
          // map "default" virtual runtime versions to the currently blessed actual runtime version
          val manifest = execManifests.resolveDefaultRuntime(kind) match {
            case Some(k) => k
            case None    => throw new DeserializationException(Messages.invalidRuntimeError(kind, runtimes))
          }

          manifest.attached
            .map { _ =>
              // java actions once stored the attachment in "jar" instead of "code"
              val code = obj.fields.get("code").orElse(obj.fields.get("jar")).getOrElse {
                throw new DeserializationException(
                  s"'code' must be a string or attachment object defined in 'exec' for '$kind' actions")
              }

              val main = optMainField.orElse {
                if (manifest.requireMain.exists(identity)) {
                  throw new DeserializationException(s"'main' must be a string defined in 'exec' for '$kind' actions")
                } else None
              }

              CodeExecAsAttachment(manifest, attFmt[String].read(code), main, isBinary(code, obj))
            }
            .getOrElse {
              val code: String = obj.fields.get("code") match {
                case Some(JsString(c)) => c
                case _ =>
                  throw new DeserializationException(s"'code' must be a string defined in 'exec' for '$kind' actions")
              }
              CodeExecAsString(manifest, code, optMainField)
            }
      }
    }
  }

  val isBase64Pattern = new Regex("^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)$").pattern

  def isBinaryCode(code: String): Boolean = {
    if (code != null) {
      val t = code.trim
      (t.length > 0) && (t.length % 4 == 0) && isBase64Pattern.matcher(t).matches()
    } else false
  }

  private def isBinary(code: JsValue, obj: JsObject): Boolean = {
    code match {
      case JsString(c) => isBinaryCode(c)
      case _           => obj.fields.get("binary").map(_.convertTo[Boolean]).getOrElse(false)
    }
  }
}

protected[core] object ExecMetaDataBase extends ArgNormalizer[ExecMetaDataBase] with DefaultJsonProtocol {

  // The possible values of the JSON 'kind' field for certain runtimes:
  // - Sequence because it is an intrinsic
  // - Black Box because it is a type marker
  protected[core] val SEQUENCE = "sequence"
  protected[core] val BLACKBOX = "blackbox"

  private def execManifests = ExecManifest.runtimesManifest

  override protected[core] implicit lazy val serdes = new RootJsonFormat[ExecMetaDataBase] {
    private def attFmt[T: JsonFormat] = Attachments.serdes[T]
    private lazy val runtimes: Set[String] = execManifests.knownContainerRuntimes ++ Set(SEQUENCE, BLACKBOX)

    override def write(e: ExecMetaDataBase) = e match {
      case c: CodeExecMetaDataAsString =>
        val base = Map("kind" -> JsString(c.kind), "binary" -> JsBoolean(c.binary))
        val main = c.entryPoint.map("main" -> JsString(_))
        JsObject(base ++ main)

      case a: CodeExecMetaDataAsAttachment =>
        val base =
          Map("kind" -> JsString(a.kind), "binary" -> JsBoolean(a.binary))
        val main = a.entryPoint.map("main" -> JsString(_))
        JsObject(base ++ main)

      case s @ SequenceExecMetaData(comp) =>
        JsObject("kind" -> JsString(s.kind), "components" -> comp.map(_.qualifiedNameWithLeadingSlash).toJson)

      case b: BlackBoxExecMetaData =>
        val base =
          Map(
            "kind" -> JsString(b.kind),
            "image" -> JsString(b.image.resolveImageName()),
            "binary" -> JsBoolean(b.binary))
        val main = b.entryPoint.map("main" -> JsString(_))
        JsObject(base ++ main)
    }

    override def read(v: JsValue) = {
      require(v != null)

      val obj = v.asJsObject

      val kind = obj.fields.get("kind") match {
        case Some(JsString(k)) => k.trim.toLowerCase
        case _                 => throw new DeserializationException("'kind' must be a string defined in 'exec'")
      }

      lazy val optMainField: Option[String] = obj.fields.get("main") match {
        case Some(JsString(m)) => Some(m)
        case Some(_) =>
          throw new DeserializationException(s"if defined, 'main' be a string in 'exec' for '$kind' actions")
        case None => None
      }

      lazy val binary: Boolean = obj.fields.get("binary") match {
        case Some(JsBoolean(b)) => b
        case _                  => throw new DeserializationException("'binary' must be a boolean defined in 'exec'")
      }

      kind match {
        case ExecMetaDataBase.SEQUENCE =>
          val comp: Vector[FullyQualifiedEntityName] = obj.fields.get("components") match {
            case Some(JsArray(components)) => components map (FullyQualifiedEntityName.serdes.read(_))
            case Some(_)                   => throw new DeserializationException(s"'components' must be an array")
            case None                      => throw new DeserializationException(s"'components' must be defined for sequence kind")
          }
          SequenceExecMetaData(comp)

        case ExecMetaDataBase.BLACKBOX =>
          val image: ImageName = obj.fields.get("image") match {
            case Some(JsString(i)) => ImageName.fromString(i).get // throws deserialization exception on failure
            case _ =>
              throw new DeserializationException(
                s"'image' must be a string defined in 'exec' for '${Exec.BLACKBOX}' actions")
          }
          val native = execManifests.skipDockerPull(image)
          BlackBoxExecMetaData(image, optMainField, native, binary)

        case _ =>
          // map "default" virtual runtime versions to the currently blessed actual runtime version
          val manifest = execManifests.resolveDefaultRuntime(kind) match {
            case Some(k) => k
            case None    => throw new DeserializationException(s"kind '$kind' not in $runtimes")
          }

          manifest.attached
            .map { a =>
              val main = optMainField.orElse {
                if (manifest.requireMain.exists(identity)) {
                  throw new DeserializationException(s"'main' must be a string defined in 'exec' for '$kind' actions")
                } else None
              }

              CodeExecMetaDataAsAttachment(manifest, binary, main)
            }
            .getOrElse {
              CodeExecMetaDataAsString(manifest, binary, optMainField)
            }
      }
    }
  }
}
