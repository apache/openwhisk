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

import pureconfig.loadConfigOrThrow

import scala.util.{Failure, Success, Try}
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.entity.Attachments._
import org.apache.openwhisk.core.entity.Attachments.Attached._
import fastparse.all._

/**
 * Reads manifest of supported runtimes from configuration file and stores
 * a representation of each runtime which is used for serialization and
 * deserialization. This singleton must be initialized.
 */
protected[core] object ExecManifest {

  /**
   * Required properties to initialize this singleton via WhiskConfig.
   */
  protected[core] def requiredProperties = Map(WhiskConfig.runtimesManifest -> null)

  /**
   * Reads runtimes manifest from WhiskConfig and initializes the
   * singleton Runtime instance.
   *
   * @param config a valid configuration
   * @param manifestOverride an optional inline manifest (used for testing)
   * @return the manifest if initialized successfully, or an failure
   */
  protected[core] def initialize(config: WhiskConfig, manifestOverride: Option[String] = None): Try[Runtimes] = {
    val rmc = loadConfigOrThrow[RuntimeManifestConfig](ConfigKeys.runtimes)
    val mf = Try(manifestOverride.getOrElse(config.runtimesManifest).parseJson.asJsObject).flatMap(runtimes(_, rmc))
    mf.foreach(m => manifest = Some(m))
    mf
  }

  /**
   * Gets existing runtime manifests.
   *
   * @return singleton Runtimes instance previous initialized from WhiskConfig
   * @throws IllegalStateException if singleton was not previously initialized
   */
  @throws[IllegalStateException]
  protected[core] def runtimesManifest: Runtimes = {
    manifest.getOrElse {
      throw new IllegalStateException("Runtimes manifest is not initialized.")
    }
  }

  private var manifest: Option[Runtimes] = None

  /**
   * @param config a configuration object as JSON
   * @return Runtimes instance
   */
  protected[entity] def runtimes(config: JsObject, runtimeManifestConfig: RuntimeManifestConfig): Try[Runtimes] = Try {
    val runtimes = config.fields
      .get("runtimes")
      .map(_.convertTo[Map[String, Set[RuntimeManifest]]].map {
        case (name, versions) =>
          RuntimeFamily(name, versions.map { mf =>
            val img = ImageName(mf.image.name, mf.image.prefix, mf.image.tag)
            mf.copy(image = img)
          })
      }.toSet)

    val blackbox = config.fields
      .get("blackboxes")
      .map(_.convertTo[Set[ImageName]].map { image =>
        ImageName(image.name, image.prefix, image.tag)
      })

    val bypassPullForLocalImages = runtimeManifestConfig.bypassPullForLocalImages
      .filter(identity)
      .flatMap(_ => runtimeManifestConfig.localImagePrefix)

    Runtimes(runtimes.getOrElse(Set.empty), blackbox.getOrElse(Set.empty), bypassPullForLocalImages)
  }

  /**
   * Misc options related to runtime manifests.
   *
   * @param bypassPullForLocalImages if true, allow images with a prefix that matches localImagePrefix
   *                                 to skip docker pull on invoker even if the image is not part of the blackbox set;
   *                                 this is useful for testing with local images that aren't published to the runtimes registry
   * @param localImagePrefix         image prefix for bypassPullForLocalImages
   */
  protected[core] case class RuntimeManifestConfig(bypassPullForLocalImages: Option[Boolean] = None,
                                                   localImagePrefix: Option[String] = None)

  /**
   * A runtime manifest describes the "exec" runtime support.
   *
   * @param kind            the name of the kind e.g., nodejs:6
   * @param deprecated      true iff the runtime is deprecated (allows get/delete but not create/update/invoke)
   * @param default         true iff the runtime is the default kind for its family (nodejs:default -> nodejs:6)
   * @param attached        true iff the source is an attachments (not inlined source)
   * @param requireMain     true iff main entry point is not optional
   * @param sentinelledLogs true iff the runtime generates stdout/stderr log sentinels after an activation
   * @param image           optional image name, otherwise inferred via fixed mapping (remove colons and append 'action')
   * @param stemCells       optional list of stemCells to be initialized by invoker per kind
   */
  protected[core] case class RuntimeManifest(kind: String,
                                             image: ImageName,
                                             deprecated: Option[Boolean] = None,
                                             default: Option[Boolean] = None,
                                             attached: Option[Attached] = None,
                                             requireMain: Option[Boolean] = None,
                                             sentinelledLogs: Option[Boolean] = None,
                                             stemCells: Option[List[StemCell]] = None)

  /**
   * A stemcell configuration read from the manifest for a container image to be initialized by the container pool.
   *
   * @param count  the number of stemcell containers to create
   * @param memory the max memory this stemcell will allocate
   */
  protected[entity] case class StemCell(count: Int, memory: ByteSize) {
    require(count > 0, "count must be positive")
  }

  /**
   * An image name for an action refers to the container image canonically as
   * "prefix/name[:tag]" e.g., "openwhisk/python3action:latest".
   */
  protected[core] case class ImageName(name: String, prefix: Option[String] = None, tag: Option[String] = None) {

    /**
     * The name of the public image for an action kind.
     */
    def publicImageName: String = {
      val p = prefix.filter(_.nonEmpty).map(_ + "/").getOrElse("")
      val t = tag.filter(_.nonEmpty).map(":" + _).getOrElse("")
      p + name + t
    }

    /**
     * The internal name of the image for an action kind relative to a registry.
     */
    def localImageName(registry: String): String = {
      val r = Option(registry)
        .filter(_.nonEmpty)
        .map { reg =>
          if (reg.endsWith("/")) reg else reg + "/"
        }
        .getOrElse("")
      val p = prefix.filter(_.nonEmpty).map(_ + "/").getOrElse("")
      val t = tag.filter(_.nonEmpty).map(":" + _).getOrElse("")
      r + p + name + t
    }

    /**
     * Overrides equals to allow match on undefined tag or when tag is latest
     * in this or that.
     */
    override def equals(that: Any) = that match {
      case ImageName(n, p, t) =>
        name == n && p == prefix && (t == tag || {
          val thisTag = tag.getOrElse(ImageName.defaultImageTag)
          val thatTag = t.getOrElse(ImageName.defaultImageTag)
          thisTag == thatTag
        })

      case _ => false
    }
  }

  protected[core] object ImageName {
    private val defaultImageTag = "latest"

    // docker image name grammar, taken from: https://github.com/docker/distribution/blob/master/reference/reference.go
    //
    // Grammar
    //
    // reference                       := name [ ":" tag ] [ "@" digest ]
    // name                            := [domain '/'] path-component ['/' path-component]*
    // domain                          := domain-component ['.' domain-component]* [':' port-number]
    // domain-component                := /([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])/
    // port-number                     := /[0-9]+/
    // path-component                  := alpha-numeric [separator alpha-numeric]*
    // alpha-numeric                   := /[a-z0-9]+/
    // separator                       := /[_.]|__|[-]*/
    //
    // tag                             := /[\w][\w.-]{0,127}/
    //
    // digest                          := digest-algorithm ":" digest-hex
    // digest-algorithm                := digest-algorithm-component [ digest-algorithm-separator digest-algorithm-component ]*
    // digest-algorithm-separator      := /[+.-_]/
    // digest-algorithm-component      := /[A-Za-z][A-Za-z0-9]*/
    // digest-hex                      := /[0-9a-fA-F]{32,}/ ; At least 128 bit digest value
    private val lowercaseLetters = P(CharIn('a' to 'z'))
    private val uppercaseLetters = P(CharIn('A' to 'Z'))
    private val letters = P(lowercaseLetters | uppercaseLetters)
    private val digits = P(CharIn('0' to '9'))

    private val alphaNumeric = P(lowercaseLetters | digits)
    private val alphaNumericWithUpper = P(letters | digits)
    private val word = P(alphaNumericWithUpper | "_")

    private val digestHex = P(digits | CharIn(('a' to 'f') ++ ('A' to 'F'))).rep(min = 32)
    private val digestAlgorithmComponent = P(letters ~ alphaNumericWithUpper.rep)
    private val digestAlgorithmSeperator = P("+" | "." | "-" | "_")
    private val digestAlgorithm = P(digestAlgorithmComponent.rep(min = 1, sep = digestAlgorithmSeperator))
    private val digest = P(digestAlgorithm ~ ":" ~ digestHex)

    private val tag = P(word ~ (word | "." | "-").rep(max = 127))

    private val separator = P("_" | "." | "__" | "-".rep)
    private val pathComponent = P(alphaNumeric.rep(min = 1, sep = separator))
    private val portNumber = P(digits.rep(min = 1))
    // FIXME: this is not correct yet. It accepts "-" as the beginning and end of a domain
    private val domainComponent = P(alphaNumericWithUpper | "-").rep
    private val domain = P(domainComponent.rep(min = 1, sep = ".") ~ (":" ~ portNumber).?)
    private val name = P((domain.! ~ "/").? ~ pathComponent.!.rep(min = 1, sep = "/"))

    private val reference = P(Start ~ name ~ (":" ~ tag.!).? ~ ("@" ~ digest.!).? ~ End)

    /**
     * Constructs an ImageName from a string. This method checks that the image name conforms
     * to the Docker naming. As a result, failure to deserialize a string will throw an exception
     * which fails the Try. Callers could use this to short-circuit operations (CRUD or activation).
     * Internal container names use the proper constructor directly.
     */
    def fromString(s: String): Try[ImageName] = {
      reference.parse(s) match {
        case Parsed.Success((registry, imagePathParts, imageTag, _), _) =>
          // imagePathParts has at least one element per the parser above
          val prefix = (registry ++ imagePathParts.dropRight(1)).mkString("/")
          val imageName = imagePathParts.last

          Success(ImageName(imageName, if (prefix.nonEmpty) Some(prefix) else None, imageTag))
        case Parsed.Failure(_, _, _) =>
          Failure(DeserializationException("could not parse image name"))
      }
    }
  }

  /**
   * A runtime family manifest is a collection of runtimes grouped by a family (e.g., swift with versions swift:2 and swift:3).
   *
   * @param name runtime family
   * @version set of runtime manifests
   */
  protected[entity] case class RuntimeFamily(name: String, versions: Set[RuntimeManifest])

  /**
   * A collection of runtime families.
   *
   * @param runtimes                 set of supported runtime families
   * @param blackboxImages           set of blackbox container images
   * @param bypassPullForLocalImages container image prefix that is exempted from docker pull operations
   */
  protected[core] case class Runtimes(runtimes: Set[RuntimeFamily],
                                      blackboxImages: Set[ImageName],
                                      bypassPullForLocalImages: Option[String]) {

    val knownContainerRuntimes: Set[String] = runtimes.flatMap(_.versions.map(_.kind))

    val manifests: Map[String, RuntimeManifest] = {
      runtimes.flatMap {
        _.versions.map { m =>
          m.kind -> m
        }
      }.toMap
    }

    def skipDockerPull(image: ImageName): Boolean = {
      blackboxImages.contains(image) ||
      image.prefix.flatMap(p => bypassPullForLocalImages.map(_ == p)).getOrElse(false)
    }

    def toJson: JsObject = {
      runtimes
        .map { family =>
          family.name -> family.versions.map {
            case rt =>
              JsObject(
                "kind" -> rt.kind.toJson,
                "image" -> rt.image.publicImageName.toJson,
                "deprecated" -> rt.deprecated.getOrElse(false).toJson,
                "default" -> rt.default.getOrElse(false).toJson,
                "attached" -> rt.attached.isDefined.toJson,
                "requireMain" -> rt.requireMain.getOrElse(false).toJson)
          }
        }
        .toMap
        .toJson
        .asJsObject
    }

    def resolveDefaultRuntime(kind: String): Option[RuntimeManifest] = {
      kind match {
        case defaultSplitter(family) => defaultRuntimes.get(family).flatMap(manifests.get(_))
        case _                       => manifests.get(kind)
      }
    }

    /**
     * Collects all runtimes for which there is a stemcell configuration defined
     *
     * @return list of runtime manifests with stemcell configurations
     */
    def stemcells: Map[RuntimeManifest, List[StemCell]] = {
      manifests
        .flatMap {
          case (_, m) => m.stemCells.map(m -> _)
        }
        .filter(_._2.nonEmpty)
    }

    private val defaultRuntimes: Map[String, String] = {
      runtimes.map { family =>
        family.versions.filter(_.default.exists(identity)).toList match {
          case Nil if family.versions.size == 1 => family.name -> family.versions.head.kind
          case Nil                              => throw new IllegalArgumentException(s"${family.name} has multiple versions, but no default.")
          case d :: Nil                         => family.name -> d.kind
          case ds =>
            throw new IllegalArgumentException(s"Found more than one default for ${family.name}: ${ds.mkString(",")}.")
        }
      }.toMap
    }

    private val defaultSplitter = "([a-z0-9]+):default".r
  }

  protected[entity] implicit val imageNameSerdes: RootJsonFormat[ImageName] = jsonFormat3(ImageName.apply)

  protected[entity] implicit val stemCellSerdes: RootJsonFormat[StemCell] = {
    import org.apache.openwhisk.core.entity.size.serdes
    jsonFormat2(StemCell.apply)
  }

  protected[entity] implicit val runtimeManifestSerdes: RootJsonFormat[RuntimeManifest] = jsonFormat8(RuntimeManifest)

}
