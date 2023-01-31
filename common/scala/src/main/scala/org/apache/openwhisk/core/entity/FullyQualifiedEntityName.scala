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

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.json._
import org.apache.openwhisk.core.entity.size.SizeString

/**
 * A FullyQualifiedEntityName (qualified name) is a triple consisting of
 * - EntityPath: the namespace and package where the entity is located
 * - EntityName: the name of the entity
 * - Version: the semantic version of the resource
 * - Binding : the entity path of the package binding, it can be used by entities that support binding
 */
protected[core] case class FullyQualifiedEntityName(path: EntityPath,
                                                    name: EntityName,
                                                    version: Option[SemVer] = None,
                                                    binding: Option[EntityPath] = None)
    extends ByteSizeable {
  private val qualifiedName: String = path + EntityPath.PATHSEP + name

  /** Resolves default namespace in path to given name if the root path is the default namespace. */
  def resolve(namespace: EntityName) = FullyQualifiedEntityName(path.resolveNamespace(namespace), name, version)

  /** @return full path including name, i.e., "path/name" */
  def fullPath: EntityPath = path.addPath(name)

  /**
   * Creates new fully qualified entity name that shifts the name into the path and adds a new name:
   * (p, n).add(x) -> (p/n, x).
   *
   * @return new fully qualified name
   */
  def add(n: EntityName) = FullyQualifiedEntityName(path.addPath(name), n)

  def toDocId = new DocId(qualifiedName)
  def namespace: EntityName = path.root
  def qualifiedNameWithLeadingSlash: String = EntityPath.PATHSEP + qualifiedName
  def asString = path.addPath(name) + version.map("@" + _.toString).getOrElse("")
  def serialize = FullyQualifiedEntityName.serdes.write(this).compactPrint

  override def size = qualifiedName.sizeInBytes
  override def toString = asString
  override def hashCode = qualifiedName.hashCode
}

protected[core] object FullyQualifiedEntityName extends DefaultJsonProtocol {
  // must use jsonFormat with explicit field names and order because class extends a trait
  private val caseClassSerdes = jsonFormat(FullyQualifiedEntityName.apply _, "path", "name", "version", "binding")

  protected[core] val serdes = new RootJsonFormat[FullyQualifiedEntityName] {
    def write(n: FullyQualifiedEntityName) = caseClassSerdes.write(n)

    def read(value: JsValue) =
      Try {
        value match {
          case JsObject(fields) => caseClassSerdes.read(value)
          // tolerate dual serialization modes; Exec serializes a sequence of fully qualified names
          // by their document id which excludes the version (hence it is just a string)
          case JsString(name) => EntityPath(name).toFullyQualifiedEntityName
          case _              => deserializationError("fully qualified name malformed")
        }
      } match {
        case Success(s)                           => s
        case Failure(t: IllegalArgumentException) => deserializationError(t.getMessage)
        case Failure(t)                           => deserializationError("fully qualified name malformed")
      }
  }

  // alternate serializer that drops version
  protected[entity] val serdesAsDocId = new RootJsonFormat[FullyQualifiedEntityName] {
    def write(n: FullyQualifiedEntityName) = n.toDocId.toJson
    def read(value: JsValue) =
      Try {
        value match {
          case JsString(name) => EntityPath(name).toFullyQualifiedEntityName
          case _              => deserializationError("fully qualified name malformed")
        }
      } match {
        case Success(s)                           => s
        case Failure(t: IllegalArgumentException) => deserializationError(t.getMessage)
        case Failure(t)                           => deserializationError("fully qualified name malformed")
      }
  }

  protected[core] def parse(msg: String) = Try(serdes.read(msg.parseJson))

  /**
   * Converts the name to a fully qualified name.
   * There are 3 cases:
   * - name is not a valid EntityPath => error
   * - name is a valid single segment with a leading slash => error
   * - name is a valid single segment without a leading slash => map it to user namespace, default package
   * - name is a valid multi segment with a leading slash => treat it as fully qualified name (max segments allowed: 3)
   * - name is a valid multi segment without a leading slash => treat it as package name and resolve it to the user namespace (max segments allowed: 3)
   *
   * The last case is ambiguous as '/namespace/action' and 'package/action' will be the same EntityPath value.
   * The action should use a fully qualified result to avoid the ambiguity.
   *
   * @param name name of the action to fully qualify
   * @param namespace the user namespace for the simple resolution
   * @return Some(FullyQualifiedName) if the name is valid otherwise None
   */
  protected[core] def resolveName(name: JsValue, namespace: EntityName): Option[FullyQualifiedEntityName] = {
    name match {
      case v @ JsString(s) =>
        Try(v.convertTo[EntityPath]).toOption
          .flatMap { path =>
            val n = path.segments
            val leadingSlash = s.startsWith(EntityPath.PATHSEP)
            if (n < 1 || n > 3 || (leadingSlash && n == 1) || (!leadingSlash && n > 3)) None
            else if (leadingSlash || n == 3) Some(path)
            else Some(namespace.toPath.addPath(path))
          }
          .map(_.resolveNamespace(namespace).toFullyQualifiedEntityName)
      case _ => None
    }
  }
}
