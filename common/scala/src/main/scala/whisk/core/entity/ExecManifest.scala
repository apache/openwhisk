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

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.WhiskConfig
import whisk.core.entity.Attachments._
import whisk.core.entity.Attachments.Attached._

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
     * @param reinit re-initialize singleton iff true
     * @return true if initialized successfully, or if previously initialized
     */
    protected[core] def initialize(config: WhiskConfig, reinit: Boolean = false): Boolean = {
        if (manifest.isEmpty || reinit) {
            Try(config.runtimesManifest.parseJson.asJsObject)
                .flatMap(runtimes(_))
                .map(m => manifest = Some(m))
                .isSuccess
        } else true
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
            throw new IllegalStateException("Runtimes manifest is not initialized")
        }
    }

    private var manifest: Option[Runtimes] = None

    /**
     * @param config a configuration object as JSON
     * @return Runtimes instance
     */
    protected[entity] def runtimes(config: JsObject): Try[Runtimes] = Try {
        Runtimes(config.convertTo[Map[String, Set[RuntimeManifest]]].map {
            case (name, versions) => RuntimeFamily(name, versions)
        }.toSet)
    }

    /**
     * A runtime manifest describes the "exec" runtime support.
     *
     * @param kind the name of the kind e.g., nodejs:6
     * @param deprecated true iff the runtime is deprecated (allows get/delete but not create/update/invoke)
     * @param default true iff the runtime is the default kind for its family (nodejs:default -> nodejs:6)
     * @param attached true iff the source is an attachments (not inlined source)
     * @param requireMain true iff main entry point is not optional
     * @param sentinelledLogs true iff the runtime generates stdout/stderr log sentinels after an activation
     * @param image optional image name, otherwise inferred via fixed mapping (remove colons and append 'action')
     */
    protected[entity] case class RuntimeManifest(
        kind: String,
        deprecated: Option[Boolean] = None,
        default: Option[Boolean] = None,
        attached: Option[Attached] = None,
        requireMain: Option[Boolean] = None,
        sentinelledLogs: Option[Boolean] = None,
        image: Option[String] = None) {
    }

    /**
     * A runtime family manifest is a collection of runtimes grouped by a family (e.g., swift with versions swift:2 and swift:3).
     * @param family runtime family
     * @version set of runtime manifests
     */
    protected[entity] case class RuntimeFamily(name: String, versions: Set[RuntimeManifest])

    /**
     * A collection of runtime families.
     *
     * @param set of supported runtime families
     */
    protected[core] case class Runtimes(runtimes: Set[RuntimeFamily]) {
        val knownContainerRuntimes: Set[String] = runtimes.flatMap(_.versions.map(_.kind))

        def resolveDefaultRuntime(kind: String): Option[RuntimeManifest] = {
            kind match {
                case defaultSplitter(family) => defaultRuntimes.get(family).flatMap(manifests.get(_))
                case _                       => manifests.get(kind)
            }
        }

        private val manifests: Map[String, RuntimeManifest] = {
            runtimes.flatMap {
                _.versions.map {
                    m => m.kind -> m
                }
            }.toMap
        }

        private val defaultRuntimes: Map[String, String] = {
            runtimes.map { family =>
                family.versions.filter(_.default.exists(identity)).toList match {
                    case Nil if family.versions.size == 1  => family.name -> family.versions.toSeq(0).kind
                    case Nil                               => throw new IllegalArgumentException(s"${family.name} has multiple versions, but no default")
                    case d :: Nil                          => family.name -> d.kind
                    case ds                                => throw new IllegalArgumentException(s"found more than one default for ${family.name}: ${ds.mkString(",")}")
                }
            }.toMap
        }

        private val defaultSplitter = "([a-z0-9]+):default".r
    }

    protected[entity] implicit val runtimeManifestSerdes = jsonFormat7(RuntimeManifest)
}

protected[core] object ExecImageName {
    /**
     * Gets the container image name for the action.
     * If the action uses a known exec manifest for which no docker pull operation is required,
     * rewrite the container name to use the local registry.
     *
     * @return image name for container to run action
     */
    def containerImageName(registry: String, prefix: String, exec: CodeExec[_], tag: String): String = {
        if (!exec.pull) {
            val image = if (exec.image != Exec.BLACKBOX_SKELETON) exec.image else exec.image.split("/")(1)
            localImageName(registry, prefix, image, tag)
        } else exec.image
    }

    def localImageName(registry: String, prefix: String, image: String, tag: String): String = {
        val r = Option(registry).filter(_.nonEmpty).map { reg =>
            if (reg.endsWith("/")) reg else reg + "/"
        }.getOrElse("")
        val p = Option(prefix).filter(_.nonEmpty).map(_ + "/").getOrElse("")
        r + p + image + ":" + tag
    }
}
