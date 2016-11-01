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
protected[core] case class FullyQualifiedEntityName(path: EntityPath, name: EntityName, version: Option[SemVer] = None) extends ByteSizeable {
    private val qualifiedName = WhiskEntity.qualifiedName(path, name)
    def toDocId = DocId(qualifiedName)
    def pathToDocId = DocId(path())
    def qualifiedNameWithLeadingSlash = EntityPath.PATHSEP + qualifiedName
    override def size = qualifiedName.sizeInBytes
    override def toString = path.addpath(name) + version.map("@" + _.toString).getOrElse("")
}

protected[core] object FullyQualifiedEntityName extends DefaultJsonProtocol {
    // must use jsonFormat with explicit field names and order because class extends a trait
    private val caseClassSerdes = jsonFormat(FullyQualifiedEntityName.apply _, "path", "name", "version")

    protected[core] implicit val serdes = new RootJsonFormat[FullyQualifiedEntityName] {
        def write(n: FullyQualifiedEntityName) = caseClassSerdes.write(n)

        def read(value: JsValue) = Try {
            value match {
                case JsObject(fields) => caseClassSerdes.read(value)
                // tolerate dual serialization modes; Exec serializes a sequence of fully qualified names
                // by their document id which excludes the version (hence it is just a string)
                case JsString(name)   => EntityPath(name).toFullyQualifiedEntityName
                case _                => deserializationError("fully qualified name malformed")
            }
        } match {
            case Success(s)                           => s
            case Failure(t: IllegalArgumentException) => deserializationError(t.getMessage)
            case Failure(t)                           => deserializationError("fully qualified name malformed")
        }
    }
}
