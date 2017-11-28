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

package whisk.core.entity

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.json._
import whisk.core.entity.size.SizeString

/**
 * A FullyQualifiedEntityName (qualified name) is a triple consisting of
 * - EntityPath: the namespace and package where the entity is located
 * - EntityName: the name of the entity
 * - Version: the semantic version of the resource
 */
protected[core] case class FullyQualifiedEntityName(path: EntityPath, name: EntityName, version: Option[SemVer] = None)
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

  def toDocId = DocId(qualifiedName)
  def namespace: EntityName = path.root
  def qualifiedNameWithLeadingSlash: String = EntityPath.PATHSEP + qualifiedName
  def asString = path.addPath(name) + version.map("@" + _.toString).getOrElse("")

  override def size = qualifiedName.sizeInBytes
  override def toString = asString
  override def hashCode = qualifiedName.hashCode
}

protected[core] object FullyQualifiedEntityName extends DefaultJsonProtocol {
  // must use jsonFormat with explicit field names and order because class extends a trait
  private val caseClassSerdes = jsonFormat(FullyQualifiedEntityName.apply _, "path", "name", "version")

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
}
