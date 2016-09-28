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
import scala.util.Failure
import scala.util.Success

import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError

/**
 * EntityPath is a path string of allowed characters. The path consists of parts each of which
 * must be a valid EntityName, separated by the EntityPath separator character. The private
 * constructor accepts a validated sequence of path parts and can reconstruct the path
 * from it.
 *
 * N. B.   The qualified name of an entity is intended to be a pair of an (EntityPath,EntityName)
 * However, right now lots of code abuses the EntityPath to mean qualified name.
 * TODO This needs to be fixed.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param path the sequence of parts that make up a namespace path
 */
protected[core] class EntityPath private (val path: Seq[String]) extends AnyVal {
    def namespace = path.foldLeft("")((a, b) => if (a != "") a.trim + EntityPath.PATHSEP + b.trim else b.trim)
    def addpath(e: EntityName) = EntityPath(path :+ e.name)
    def root = EntityPath(Seq(path(0)))
    def last = EntityName(path.last)
    def defaultPackage = path.size == 1 // if only one element in the path, then it's the namespace with a default package
    def toJson = JsString(namespace)
    def apply() = namespace
    override def toString = namespace

    /**
     * Replaces root of this path with given namespace iff the root is
     * the default namespace.
     */
    def resolveNamespace(newNamespace: EntityName): EntityPath = {
        // check if namespace is default
        if (root == EntityPath.DEFAULT) {
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
        require(path.size > 1, "fully qualified entity name must contain at least the namespace and the name of the entity")
        val name = last
        val newPath = EntityPath(path.dropRight(1))
        FullyQualifiedEntityName(newPath, name)
    }
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
        require(parts.forall { s => s != null && s.matches(EntityName.REGEX) }, s"path contains invalid parts ${parts.toString}")
        new EntityPath(parts)
    }

    /** Returns true iff the path is a valid namespace path. */
    protected[core] def validate(path: String): Boolean = {
        Try { EntityPath(path) } map { _ => true } getOrElse false
    }

    implicit val serdes = new RootJsonFormat[EntityPath] {
        def write(n: EntityPath) = n.toJson

        def read(value: JsValue) = Try {
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
    def apply() = name
    def toJson = JsString(name)
    def toPath = EntityPath(name)
    override def toString = name
}

protected[core] object EntityName {
    /**
     * Allowed path part or entity name format (excludes path separator): first character
     * is a letter|digit|underscore, followed by one or more allowed characters in [\w@ .-].
     * The name may not have trailing white space.
     */
    protected[core] val REGEX = """\A([\w]|[\w][\w@ .-]*[\w@.-]+)\z"""

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
        require(name != null && name.matches(REGEX), s"name [$name] is not allowed")
        new EntityName(name)
    }

    implicit val serdes = new RootJsonFormat[EntityName] {
        def write(n: EntityName) = n.toJson

        def read(value: JsValue) = Try {
            val JsString(name) = value
            EntityName(name)
        } getOrElse deserializationError("entity name malformed")
    }
}

/**
 * A FullyQualifiedEntityName (qualified name) is a triple consisting of
 * - EntityPath: the namespace and package where the entity is located
 * - EntityName: the name of the entity
 * - Version: the semantic version of the resource
 */
protected[core] case class FullyQualifiedEntityName(path: EntityPath, name: EntityName, version: Option[SemVer] = None) {
    override def toString = path.addpath(name) + version.map("@" + _.toString).getOrElse("")
    def toDocId = DocId(WhiskEntity.qualifiedName(path, name))
    def pathToDocId = DocId(path())
}

protected[core] object FullyQualifiedEntityName {
    implicit val serdes = new RootJsonFormat[FullyQualifiedEntityName] {
        def write(n: FullyQualifiedEntityName) = JsString(EntityPath.PATHSEP + WhiskEntity.qualifiedName(n.path, n.name))

        def read(value: JsValue) = Try {
            val JsString(name) = value
            EntityPath(name).toFullyQualifiedEntityName
        } match {
            case Success(s)                           => s
            case Failure(t: IllegalArgumentException) => deserializationError(t.getMessage)
            case Failure(t)                           => deserializationError("fully qualified name malformed")
        }
    }
}
