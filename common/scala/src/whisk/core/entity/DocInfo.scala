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

import whisk.core.entity.ArgNormalizer.trim
import scala.util.Try
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.JsString
import spray.json.deserializationError

/**
 * A DocId is the document id === primary key in the datastore.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param id the document id, required not null
 */
protected[core] class DocId private (val id: String) extends AnyVal {
    def apply() = id
    protected[core] def asDocInfo = DocInfo(this)
    protected[core] def asDocInfo(rev: DocRevision) = DocInfo(this, rev)
    protected[entity] def toJson = JsString(id)
    override def toString = id
}

/**
 * A DocRevision is the document revision, an opaque value that may be
 * determined by the datastore.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param rev the document revision, optional
 */
protected[core] class DocRevision private (val rev: String) extends AnyVal {
    def apply() = rev
    def empty = rev == null
    override def toString = rev
}

/**
 * Document Info wrapping the document id and revision. The constructor
 * is protected to make sure id and rev are well formed and defined. Use
 * one of the factories in the companion object where necessary. Since
 * the id and rev are values, the type system ensures they are not null.
 *
 * @param id the document id
 * @param rev the document revision, optional; this is an opaque value determined by the datastore
 */
protected[core] case class DocInfo protected[entity] (id: DocId, rev: DocRevision = DocRevision()) {
    def apply() = id()

    override def toString =
        s"""|id: ${id()}
            |rev: ${rev()}""".stripMargin.replace("\n", ", ")

    override def hashCode = {
        if (rev.empty) {
            id.hashCode
        } else {
            "$id.$rev".hashCode
        }
    }
}

protected[core] object DocId extends ArgNormalizer[DocId] {
    /**
     * Unapply method for convenience of case matching.
     */
    def unapply(s: String): Option[DocId] = Try(DocId(s)).toOption

    implicit val serdes = new RootJsonFormat[DocId] {
        def write(d: DocId) = d.toJson

        def read(value: JsValue) = Try {
            val JsString(s) = value
            new DocId(s)
        } getOrElse deserializationError("doc id malformed")
    }
}

protected[core] object DocRevision {
    /**
     * Creates a DocRevision. Normalizes the revision if necessary.
     *
     * @param s is the document revision as a string, may be null
     * @return DocRevision
     */
    protected[core] def apply(s: String = null): DocRevision = new DocRevision(trim(s))
}

protected[core] object DocInfo {
    /**
     * Creates a DocInfo with id set to the argument and no revision.
     *
     * @param id is the document identifier, must be defined
     * @throws IllegalArgumentException if id is null or empty
     */
    @throws[IllegalArgumentException]
    protected[core] def apply(id: String): DocInfo = DocInfo(DocId(id))

    /**
     * Creates a DocInfo with id and revision per the provided arguments.
     *
     * @param id is the document identifier, must be defined
     * @param rev the document revision, optional
     * @return DocInfo for id and revision
     * @throws IllegalArgumentException if id is null or empty
     */
    @throws[IllegalArgumentException]
    protected[core] def !(id: String, rev: String): DocInfo = DocInfo(DocId(id), DocRevision(rev))
}
