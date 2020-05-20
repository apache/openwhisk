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

import pureconfig._
import pureconfig.generic.auto._

import scala.util.{Failure, Success, Try}
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.entity.Attachments._
import org.apache.openwhisk.core.entity.Attachments.Attached._
import fastparse._
import NoWhitespace._

import scala.concurrent.duration.{Duration, FiniteDuration}

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
            val img = ImageName(mf.image.name, mf.image.registry, mf.image.prefix, mf.image.tag)
            mf.copy(image = img)
          })
      }.toSet)

    val blackbox = config.fields
      .get("blackboxes")
      .map(_.convertTo[Set[ImageName]].map { image =>
        ImageName(image.name, image.registry, image.prefix, image.tag)
      })

    val bypassPullForLocalImages = runtimeManifestConfig.bypassPullForLocalImages
      .flatMap {
        case true  => runtimeManifestConfig.localImagePrefix
        case false => None
      }

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
   * @param initialCount  the initial number of stemcell containers to create
   * @param memory the max memory this stemcell will allocate
   * @param reactive the reactive prewarming prewarmed config, which is disabled by default
   */
  protected[entity] case class StemCell(initialCount: Int,
                                        memory: ByteSize,
                                        reactive: Option[ReactivePrewarmingConfig] = None) {
    require(initialCount > 0, "initialCount must be positive")
  }

  /**
   * A stemcell's ReactivePrewarmingConfig configuration
   *
   * @param minCount the max number of stemcell containers to exist
   * @param maxCount  the max number of stemcell containers to create
   * @param ttl time to live of the prewarmed container
   * @param threshold the executed activation number of cold start in previous one minute
   * @param increment increase per increment prewarmed number under per threshold activations
   */
  protected[core] case class ReactivePrewarmingConfig(minCount: Int,
                                                      maxCount: Int,
                                                      ttl: FiniteDuration,
                                                      threshold: Int,
                                                      increment: Int) {
    require(
      minCount >= 0 && minCount <= maxCount,
      "minCount must be be greater than 0 and less than or equal to maxCount")
    require(maxCount > 0, "maxCount must be positive")
    require(ttl.toMillis > 0, "ttl must be positive")
    require(threshold > 0, "threshold must be positive")
    require(increment > 0 && increment <= maxCount, "increment must be positive and less than or equal to maxCount")
  }

  /**
   * An image name for an action refers to the container image canonically as
   * "prefix/name[:tag]" e.g., "openwhisk/python3action:latest".
   */
  protected[core] case class ImageName(name: String,
                                       registry: Option[String] = None,
                                       prefix: Option[String] = None,
                                       tag: Option[String] = None) {

    /**
     * The actual name of the image for an action kind resolved by registry setting.
     */
    def resolveImageName(systemRegistry: Option[String] = None): String = {
      val r = Option(registry.getOrElse(systemRegistry.getOrElse((""))))
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
      case ImageName(n, r, p, t) =>
        name == n && registry == r && p == prefix && (t == tag || {
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
    private def lowercaseLetters[_: P] = P(CharIn("a-z"))
    private def uppercaseLetters[_: P] = P(CharIn("a-Z"))
    private def letters[_: P] = P(lowercaseLetters | uppercaseLetters)
    private def digits[_: P] = P(CharIn("0-9"))

    private def alphaNumeric[_: P] = P(lowercaseLetters | digits)
    private def alphaNumericWithUpper[_: P] = P(letters | digits)
    private def word[_: P] = P(alphaNumericWithUpper | "_")

    private def digestHex[_: P] = P(digits | CharIn("a-fA-F")).rep(32)
    private def digestAlgorithmComponent[_: P] = P(letters ~ alphaNumericWithUpper.rep)
    private def digestAlgorithmSeperator[_: P] = P("+" | "." | "-" | "_")
    private def digestAlgorithm[_: P] = P(digestAlgorithmComponent.rep(min = 1, sep = digestAlgorithmSeperator))
    private def digest[_: P] = P(digestAlgorithm ~ ":" ~ digestHex)

    private def tag[_: P] = P(word ~ (word | "." | "-").rep(max = 127))

    private def separator[_: P] = P("_" | "." | "__" | "-".rep)
    private def pathComponent[_: P] = P(alphaNumeric.rep(min = 1, sep = separator))
    private def portNumber[_: P] = P(digits.rep(1))
    // FIXME: this is not correct yet. It accepts "-" as the beginning and end of a domain
    private def domainComponent[_: P] = P(alphaNumericWithUpper | "-").rep
    private def domain[_: P] =
      P((domainComponent
        .rep(min = 2, sep = ".") ~ (":" ~ portNumber).?) | (domainComponent.rep(min = 1, sep = ".") ~ ":" ~ portNumber))
    private def name[_: P] = P((domain.! ~ "/").? ~ pathComponent.!.rep(min = 1, sep = "/"))

    private def reference[_: P] = P(Start ~ name ~ (":" ~ tag.!).? ~ ("@" ~ digest.!).? ~ End)

    /**
     * Constructs an ImageName from a string. This method checks that the image name conforms
     * to the Docker naming. As a result, failure to deserialize a string will throw an exception
     * which fails the Try. Callers could use this to short-circuit operations (CRUD or activation).
     * Internal container names use the proper constructor directly.
     */
    def fromString(s: String): Try[ImageName] = {
      parse(s, reference(_)) match {
        case Parsed.Success((registry, imagePathParts, imageTag, _), _) =>
          // imagePathParts has at least one element per the parser above
          val prefix = (imagePathParts.dropRight(1)).mkString("/")
          val imageName = imagePathParts.last

          Success(ImageName(imageName, registry, if (prefix.nonEmpty) Some(prefix) else None, imageTag))
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
                "image" -> rt.image.resolveImageName().toJson,
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

  protected[entity] implicit val imageNameSerdes: RootJsonFormat[ImageName] = jsonFormat4(ImageName.apply)

  protected[entity] implicit val ttlSerdes: RootJsonFormat[FiniteDuration] = new RootJsonFormat[FiniteDuration] {
    override def write(finiteDuration: FiniteDuration): JsValue = JsString(finiteDuration.toString)

    override def read(value: JsValue): FiniteDuration = value match {
      case JsString(s) =>
        val duration = Duration(s)
        FiniteDuration(duration.length, duration.unit)
      case _ =>
        deserializationError("time unit not supported. Only milliseconds, seconds, minutes, hours, days are supported")
    }
  }

  protected[entity] implicit val reactivePrewarmingConfigSerdes: RootJsonFormat[ReactivePrewarmingConfig] = jsonFormat5(
    ReactivePrewarmingConfig.apply)

  protected[entity] implicit val stemCellSerdes = new RootJsonFormat[StemCell] {
    import org.apache.openwhisk.core.entity.size.serdes
    val defaultSerdes = jsonFormat3(StemCell.apply)
    override def read(value: JsValue): StemCell = {
      val fields = value.asJsObject.fields
      val initialCount: Option[Int] =
        fields
          .get("initialCount")
          .orElse(fields.get("count"))
          .map(_.convertTo[Int])
      val memory: Option[ByteSize] = fields.get("memory").map(_.convertTo[ByteSize])
      val config = fields.get("reactive").map(_.convertTo[ReactivePrewarmingConfig])

      (initialCount, memory) match {
        case (Some(c), Some(m)) => StemCell(c, m, config)
        case (Some(c), None) =>
          throw new IllegalArgumentException(s"memory is required, just provide initialCount: ${c}")
        case (None, Some(m)) =>
          throw new IllegalArgumentException(s"initialCount is required, just provide memory: ${m.toString}")
        case _ => throw new IllegalArgumentException("both initialCount and memory are required")
      }
    }

    override def write(s: StemCell) = defaultSerdes.write(s)
  }

  protected[entity] implicit val runtimeManifestSerdes: RootJsonFormat[RuntimeManifest] = jsonFormat8(RuntimeManifest)

}
