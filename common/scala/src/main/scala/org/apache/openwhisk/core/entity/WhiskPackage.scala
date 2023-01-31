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

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Try
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{ArtifactStore, CacheChangeNotification, DocumentFactory}
import org.apache.openwhisk.core.entity.types.EntityStore

/**
 * WhiskPackagePut is a restricted WhiskPackage view that eschews properties
 * that are auto-assigned or derived from URI: namespace and name.
 */
case class WhiskPackagePut(binding: Option[Binding] = None,
                           parameters: Option[Parameters] = None,
                           version: Option[SemVer] = None,
                           publish: Option[Boolean] = None,
                           annotations: Option[Parameters] = None) {

  /**
   * Resolves the binding if it contains the default namespace.
   */
  protected[core] def resolve(namespace: EntityName): WhiskPackagePut = {
    WhiskPackagePut(binding.map(_.resolve(namespace)), parameters, version, publish, annotations)
  }
}

/**
 * A WhiskPackage provides an abstraction of the meta-data for a whisk package
 * or package binding.
 *
 * The WhiskPackage object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskPackage abstraction.
 *
 * @param namespace the namespace for the action
 * @param name the name of the action
 * @param binding an optional binding, None for provider, Some for binding
 * @param parameters the set of parameters to bind to the action environment
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotations the set of annotations to attribute to the package
 * @param updated the timestamp when the package is updated
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskPackage(namespace: EntityPath,
                        override val name: EntityName,
                        binding: Option[Binding] = None,
                        parameters: Parameters = Parameters(),
                        version: SemVer = SemVer(),
                        publish: Boolean = false,
                        annotations: Parameters = Parameters(),
                        override val updated: Instant = WhiskEntity.currentMillis())
    extends WhiskEntity(name, "package") {

  require(binding != null || (binding map { _ != null } getOrElse true), "binding undefined")

  /**
   * Merges parameters into existing set of parameters for package.
   * Existing parameters supersede those in p.
   */
  def inherit(p: Parameters): WhiskPackage = copy(parameters = p ++ parameters).revision[WhiskPackage](rev)

  /**
   * Merges parameters into existing set of parameters for package.
   * The parameters from p supersede parameters from this.
   */
  def mergeParameters(p: Parameters): WhiskPackage = copy(parameters = parameters ++ p).revision[WhiskPackage](rev)

  /**
   * Gets the full path for the package.
   * This is equivalent to calling this this.fullyQualifiedName(withVersion = false).fullPath.
   */
  def fullPath: EntityPath = namespace.addPath(name)

  /**
   * Gets binding for package iff this is not already a package reference.
   */
  def bind: Option[Binding] = if (binding.isEmpty) Some(Binding(namespace.root, name)) else None

  /**
   * Adds actions to package. The actions list is filtered so that only actions that
   * match the package are included (must match package namespace/name).
   */
  def withActions(actions: List[WhiskAction] = List.empty): WhiskPackageWithActions = {
    withPackageActions(actions filter { a =>
      val pkgns = binding map { b =>
        b.namespace.addPath(b.name)
      } getOrElse { namespace.addPath(name) }
      a.namespace == pkgns
    } map { a =>
      WhiskPackageAction(a.name, a.version, a.annotations)
    })
  }

  /**
   * Adds package actions to package as actions or feeds. An action is considered a feed
   * is it defined the property "feed" in the annotation. The value of the property is ignored
   * for this check.
   */
  def withPackageActions(actions: List[WhiskPackageAction] = List.empty): WhiskPackageWithActions = {
    val (feedActions, nonFeedActions) = actions.partition(_.annotations.get(Parameters.Feed).isDefined)
    WhiskPackageWithActions(this, nonFeedActions, feedActions)
  }

  def toJson = WhiskPackage.serdes.write(this).asJsObject

  /**
   * This the package summary as computed by the database view.
   * Strictly used in view testing to enforce alignment.
   */
  override def summaryAsJson = {
    JsObject(
      super.summaryAsJson.fields +
        (WhiskPackage.bindingFieldName -> binding.map(Binding.serdes.write(_)).getOrElse(JsFalse)))
  }
}

/**
 * A specialized view of a whisk action contained in a package.
 * Eschews fields that are implied by package in a GET package response..
 */
case class WhiskPackageAction(name: EntityName, version: SemVer, annotations: Parameters)

/**
 * Extends WhiskPackage to include list of actions contained in package.
 * This is used in GET package response.
 */
case class WhiskPackageWithActions(wp: WhiskPackage, actions: List[WhiskPackageAction], feeds: List[WhiskPackageAction])

object WhiskPackage
    extends DocumentFactory[WhiskPackage]
    with WhiskEntityQueries[WhiskPackage]
    with DefaultJsonProtocol {

  import WhiskActivation.instantSerdes

  val bindingFieldName = "binding"
  override val collectionName = "packages"

  /**
   * Traverses a binding recursively to find the root package and
   * merges parameters along the way if mergeParameters flag is set.
   *
   * @param db the entity store containing packages
   * @param pkg the package document id to start resolving
   * @param mergeParameters flag that indicates whether parameters should be merged during package resolution
   * @return the same package if there is no binding, or the actual reference package otherwise
   */
  def resolveBinding(db: EntityStore, pkg: DocId, mergeParameters: Boolean = false)(
    implicit ec: ExecutionContext,
    transid: TransactionId): Future[WhiskPackage] = {
    WhiskPackage.get(db, pkg) flatMap { wp =>
      // if there is a binding resolve it
      val resolved = wp.binding map { binding =>
        if (mergeParameters) {
          resolveBinding(db, binding.docid, true) map { resolvedPackage =>
            resolvedPackage.mergeParameters(wp.parameters)
          }
        } else resolveBinding(db, binding.docid)
      }
      resolved getOrElse Future.successful(wp)
    }
  }

  override implicit val serdes = {

    /**
     * Custom serdes for a binding - this property must be present in the datastore records for
     * packages so that views can map over packages vs bindings.
     */
    implicit val bindingOverride = new JsonFormat[Option[Binding]] {
      override def write(b: Option[Binding]) = Binding.optionalBindingSerializer.write(b)
      override def read(js: JsValue) = Binding.optionalBindingDeserializer.read(js)
    }
    jsonFormat8(WhiskPackage.apply)
  }

  override val cacheEnabled = true

  lazy val publicPackagesView: View = WhiskQueries.entitiesView(collection = s"$collectionName-public")

  // overridden to store encrypted parameters.
  override def put[A >: WhiskPackage](db: ArtifactStore[A], doc: WhiskPackage, old: Option[WhiskPackage])(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    super.put(
      db,
      doc.copy(parameters = doc.parameters.lock(ParameterEncryption.singleton.default)).revision[WhiskPackage](doc.rev),
      old)
  }
}

/**
 * A package binding holds a reference to the providing package
 * namespace and package name.
 */
case class Binding(namespace: EntityName, name: EntityName) {
  def fullyQualifiedName = FullyQualifiedEntityName(namespace.toPath, name)
  def docid = fullyQualifiedName.toDocId
  override def toString = fullyQualifiedName.toString

  /**
   * Returns a Binding namespace if it is the default namespace
   * to the given one, otherwise this is an identity.
   */
  def resolve(ns: EntityName): Binding = {
    namespace.toPath match {
      case EntityPath.DEFAULT => Binding(ns, name)
      case _                  => this
    }
  }
}

object Binding extends ArgNormalizer[Binding] with DefaultJsonProtocol {

  override protected[core] val serdes = jsonFormat2(Binding.apply)

  protected[entity] val optionalBindingDeserializer = new JsonReader[Option[Binding]] {
    override def read(js: JsValue) = {
      if (js == JsObject.empty) None else Some(serdes.read(js))
    }

  }

  protected[entity] val optionalBindingSerializer = new JsonWriter[Option[Binding]] {
    override def write(b: Option[Binding]) = b match {
      case None    => JsObject.empty
      case Some(n) => Binding.serdes.write(n)
    }
  }
}

object WhiskPackagePut extends DefaultJsonProtocol {
  implicit val serdes = {
    implicit val bindingSerdes = Binding.serdes
    implicit val optionalBindingSerdes = new OptionFormat[Binding] {
      override def read(js: JsValue) = Binding.optionalBindingDeserializer.read(js)
      override def write(n: Option[Binding]) = Binding.optionalBindingSerializer.write(n)
    }
    jsonFormat5(WhiskPackagePut.apply)
  }
}

object WhiskPackageAction extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat3(WhiskPackageAction.apply)
}

object WhiskPackageWithActions {
  implicit val serdes = new RootJsonFormat[WhiskPackageWithActions] {
    def write(w: WhiskPackageWithActions) = {
      val JsObject(pkg) = WhiskPackage.serdes.write(w.wp)
      JsObject(pkg + ("actions" -> w.actions.toJson) + ("feeds" -> w.feeds.toJson))
    }

    def read(value: JsValue) =
      Try {
        val pkg = WhiskPackage.serdes.read(value)
        val actions = value.asJsObject.getFields("actions") match {
          case Seq(JsArray(as)) =>
            as map { a =>
              WhiskPackageAction.serdes.read(a)
            } toList
          case _ => List.empty
        }
        val feeds = value.asJsObject.getFields("feeds") match {
          case Seq(JsArray(as)) =>
            as map { a =>
              WhiskPackageAction.serdes.read(a)
            } toList
          case _ => List.empty
        }
        WhiskPackageWithActions(pkg, actions, feeds)
      } getOrElse deserializationError("whisk package with actions malformed")
  }
}
