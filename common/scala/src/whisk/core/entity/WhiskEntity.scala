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

import spray.json.JsBoolean
import spray.json.JsObject
import whisk.core.entity.schema.EntityRecord
import java.time.Instant
import java.time.Clock

/**
 * An abstract superclass that encapsulates properties common to all whisk entities (actions, rules, triggers).
 * The class has a private contstructor argument and abstract fields so that case classes that extend this base
 * type can use the default spray JSON ser/des. An abstract entity has the following four properties.
 *
 * @param en the name of the entity, this is part of the primary key for the document
 * @param namespace the namespace for the entity as an abstract field
 * @param version the semantic version as an abstract field
 * @param publish true to share the entity and false to keep it private as an abstract field
 * @param annotation the set of annotations to attribute to the entity
 *
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
abstract class WhiskEntity protected[entity] (en: EntityName) extends WhiskDocument {

    val namespace: Namespace
    val name = en
    val version: SemVer
    val publish: Boolean
    val annotations: Parameters
    val updated = Instant.now(Clock.systemUTC())

    /**
     * The name of the entity qualified with its namespace and version for
     * creating unique keys in backend services.
     */
    final def fullyQualifiedName = WhiskEntity.fullyQualifiedName(namespace, en, version)

    /** The primary key for the entity in the datastore */
    override final def docid = DocId(WhiskEntity.qualifiedName(namespace, en))

    /**
     * @return T instead of Try[T] for convenience. The overrides must
     * wrap calls to serialize with try {} and return a Try instance.
     */
    protected def serialize[T <: EntityRecord](w: T): T = {
        w.docinfo(docinfo)
        w.namespace = namespace.toString
        w.name = name.toString
        w.version = version.toString
        w.publish = publish
        w.annotations = annotations.toGson
        w.updated = updated.toEpochMilli
        w
    }

    /**
     * @return the primary key (name) of the entity as a pithy description
     */
    override def toString = s"${this.getClass.getSimpleName}/$fullyQualifiedName"

    /**
     * A JSON view of the entity, that should match the result returned in a list operation.
     * This should be synchronized with the views computed in wipeTransientDBs.sh.
     */
    def summaryAsJson = JsObject(
        "namespace" -> namespace.toJson,
        "name" -> name.toJson,
        "version" -> version.toJson,
        WhiskEntity.sharedFieldName -> JsBoolean(publish),
        "annotations" -> annotations.toJsArray)
}

object WhiskEntity {

    val sharedFieldName = "publish"

    /**
     * Gets fully qualified name of an entity based on its namespace and entity name.
     */
    def qualifiedName(namespace: Namespace, name: EntityName) = {
        s"$namespace${Namespace.PATHSEP}$name"
    }

    /**
     * Gets fully qualified name of an activation based on its namespace and activation id.
     */
    def qualifiedName(namespace: Namespace, activationId: ActivationId) = {
        s"$namespace${Namespace.PATHSEP}$activationId"
    }

    /**
     * Gets fully qualified name of an entity based on its namespace, entity name and version.
     */
    def fullyQualifiedName(namespace: Namespace, name: EntityName, version: SemVer) = {
        s"$namespace${Namespace.PATHSEP}${versionedName(name, version)}"
    }

    /**
     * Gets versioned name of an entity based on its entity name and version.
     */
    def versionedName(name: EntityName, version: SemVer) = {
        s"$name${Namespace.PATHSEP}$version"
    }
}