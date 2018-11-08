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

import java.util.regex.Matcher

import scala.util.Try

import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import org.apache.openwhisk.http.Messages

/**
 * EntityPath is a path string of allowed characters. The path consists of parts each of which
 * must be a valid EntityName, separated by the EntityPath separator character. The private
 * constructor accepts a validated sequence of path parts and can reconstruct the path
 * from it. The path cannot be empty.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param path the sequence of parts that make up a namespace path
 */
protected[core] class EntityPath private (private val path: Seq[String]) extends AnyVal {
  def namespace: String = path.foldLeft("")((a, b) => if (a != "") a.trim + EntityPath.PATHSEP + b.trim else b.trim)

  /**
   * @return number of parts in the path.
   */
  def segments = path.length

  /**
   * Adds segment to path.
   */
  def addPath(e: EntityName) = EntityPath(path :+ e.name)

  /**
   * Concatenates given path to existin path.
   */
  def addPath(p: EntityPath) = EntityPath(path ++ p.path)

  /**
   * Computes the relative path by dropping the leftmost segment. The return is an option
   * since dropping a singleton results in an invalid path.
   */
  def relativePath: Option[EntityPath] = Try(EntityPath(path.drop(1))).toOption

  /**
   * @return the root of the path (the first segment).
   */
  def root: EntityName = EntityName(path.head)

  /**
   * @return the last segment of the path.
   */
  def last: EntityName = EntityName(path.last)

  /**
   * @return true iff the path contains exactly one segment (i.e., the namespace)
   */
  def defaultPackage: Boolean = path.size == 1

  /**
   * Replaces root of this path with given namespace iff the root is
   * the default namespace.
   */
  def resolveNamespace(newNamespace: Namespace): EntityPath = {
    resolveNamespace(newNamespace.name)
  }

  /**
   * Replaces root of this path with given namespace iff the root is
   * the default namespace.
   */
  def resolveNamespace(newNamespace: EntityName): EntityPath = {
    // check if namespace is default
    if (root.toPath == EntityPath.DEFAULT) {
      val newPath = path.updated(0, newNamespace.name)
      EntityPath(newPath)
    } else this
  }

  /**
   * Converts the path to a fully qualified name. The path must contains at least 2 parts.
   *
   * @throws IllegalArgumentException if the path does not conform to schema (at least namespace and entity name must be present0
   */
  @throws[IllegalArgumentException]
  def toFullyQualifiedEntityName = {
    require(path.size > 1, Messages.malformedFullyQualifiedEntityName)
    val name = last
    val newPath = EntityPath(path.dropRight(1))
    FullyQualifiedEntityName(newPath, name)
  }

  def toDocId = DocId(namespace)
  def toJson = JsString(namespace)
  def asString = namespace // to make explicit that this is a string conversion
  override def toString = namespace
}

protected[core] object EntityPath {

  /** Path separator */
  protected[core] val PATHSEP = "/"

  /**
   * Default namespace name. This name is not a valid entity name and is a special string
   * that allows omission of the namespace during API calls. It is only used in the URI
   * namespace extraction.
   */
  protected[core] val DEFAULT = EntityPath("_")

  /**
   * Constructs a Namespace from a string. String must be a valid path, consisting of
   * a valid EntityName separated by the Namespace separator character.
   *
   * @param path a valid namespace path
   * @return Namespace for the path
   * @throws IllegalArgumentException if the path does not conform to schema
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(path: String): EntityPath = {
    require(path != null, "path undefined")
    val parts = path.split(PATHSEP).filter { _.nonEmpty }.toSeq
    EntityPath(parts)
  }

  /**
   * Namespace is a path string of allowed characters. The path consists of parts each of which
   * must be a valid EntityName, separated by the Namespace separator character. The constructor
   * accepts a sequence of path parts and can reconstruct the path from it.
   *
   * @param path the sequence of parts that make up a namespace path
   * @throws IllegalArgumentException if any of the parts are not valid path part names
   */
  @throws[IllegalArgumentException]
  private def apply(parts: Seq[String]): EntityPath = {
    require(parts != null && parts.nonEmpty, "path undefined")
    require(parts.forall { s =>
      s != null && EntityName.entityNameMatcher(s).matches
    }, s"path contains invalid parts ${parts.toString}")
    new EntityPath(parts)
  }

  /** Returns true iff the path is a valid namespace path. */
  protected[core] def validate(path: String): Boolean = {
    Try { EntityPath(path) } map { _ =>
      true
    } getOrElse false
  }

  implicit val serdes = new RootJsonFormat[EntityPath] {
    def write(n: EntityPath) = n.toJson

    def read(value: JsValue) =
      Try {
        val JsString(name) = value
        EntityPath(name)
      } getOrElse deserializationError("namespace malformed")
  }
}

/**
 * EntityName is a string of allowed characters.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 */
protected[core] class EntityName private (val name: String) extends AnyVal {
  def asString = name // to make explicit that this is a string conversion
  def toJson = JsString(name)
  def toPath: EntityPath = EntityPath(name)
  def addPath(e: EntityName): EntityPath = toPath.addPath(e)
  def addPath(e: Option[EntityName]): EntityPath = e map { toPath.addPath(_) } getOrElse toPath
  override def toString = name
}

protected[core] object EntityName {
  protected[core] val ENTITY_NAME_MAX_LENGTH = 256

  /**
   * Allowed path part or entity name format (excludes path separator): first character
   * is a letter|digit|underscore, followed by one or more allowed characters in [\w@ .&-].
   * The name may not have trailing white space.
   */
  protected[core] val REGEX = raw"\A([\w]|[\w][\w@ .&-]{0,${ENTITY_NAME_MAX_LENGTH - 2}}[\w@.&-])\z"
  private val entityNamePattern = REGEX.r.pattern // compile once
  protected[core] def entityNameMatcher(s: String): Matcher = entityNamePattern.matcher(s)

  /**
   * Unapply method for convenience of case matching.
   */
  protected[core] def unapply(name: String): Option[EntityName] = Try(EntityName(name)).toOption

  /**
   * EntityName is a string of allowed characters.
   *
   * @param name the entity name
   * @throws IllegalArgumentException if the name does not conform to schema
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(name: String): EntityName = {
    require(name != null && entityNameMatcher(name).matches, s"name [$name] is not allowed")
    new EntityName(name)
  }

  implicit val serdes = new RootJsonFormat[EntityName] {
    def write(n: EntityName) = n.toJson

    def read(value: JsValue) =
      Try {
        val JsString(name) = value
        EntityName(name)
      } getOrElse deserializationError("entity name malformed")
  }
}
