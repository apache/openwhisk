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

import org.apache.commons.lang3.StringUtils

import scala.util.Try
import spray.json.DefaultJsonProtocol
import spray.json.JsNull
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json._
import org.apache.openwhisk.core.entity.ArgNormalizer.trim

/**
 * A DocId is the document id === primary key in the datastore.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param id the document id, required not null
 */
protected[core] class DocId(val id: String) extends AnyVal {
  def asString = id // to make explicit that this is a string conversion
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
protected[core] class DocRevision private (val rev: String) extends AnyVal with Ordered[DocRevision] {
  def asString = rev // to make explicit that this is a string conversion
  def empty = rev == null
  override def toString = rev
  def serialize = DocRevision.serdes.write(this).compactPrint

  override def compare(that: DocRevision): Int = {
    if (this.empty && that.empty) {
      0
    } else if (this.empty) {
      -1
    } else if (that.empty) {
      1
    } else {
      StringUtils.substringBefore(rev, "-").toInt - StringUtils.substringBefore(that.rev, "-").toInt
    }
  }

  def ==(that: DocRevision): Boolean = {
    this.compare(that) == 0
  }
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
protected[core] case class DocInfo protected[entity] (id: DocId, rev: DocRevision = DocRevision.empty) {
  override def toString = {
    if (rev.empty) {
      s"id: $id"
    } else {
      s"id: $id, rev: $rev"
    }
  }

  override def hashCode = {
    if (rev.empty) {
      id.hashCode
    } else {
      s"$id.$rev".hashCode
    }
  }
}

/**
 * A BulkEntityResult is wrapping the fields that are returned for a single document on a bulk-put of several documents.
 * http://docs.couchdb.org/en/2.1.0/api/database/bulk-api.html#post--db-_bulk_docs
 *
 * @param id the document id
 * @param rev the document revision, optional; this is an opaque value determined by the datastore
 * @param error the error, that occurred on trying to put this document into CouchDB
 * @param reason the error message that correspands to the error
 */
case class BulkEntityResult(id: String, rev: Option[DocRevision], error: Option[String], reason: Option[String]) {
  def toDocInfo = DocInfo(DocId(id), rev.getOrElse(DocRevision.empty))
}

protected[core] object DocId extends ArgNormalizer[DocId] {

  /**
   * Unapply method for convenience of case matching.
   */
  def unapply(s: String): Option[DocId] = Try(DocId(s)).toOption

  implicit val serdes = new RootJsonFormat[DocId] {
    def write(d: DocId) = d.toJson

    def read(value: JsValue) =
      Try {
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
  protected[core] def apply(s: String): DocRevision = new DocRevision(trim(s))

  protected[core] val empty: DocRevision = new DocRevision(null)

  protected[core] def parse(msg: String) = Try(serdes.read(msg.parseJson))

  implicit val serdes = new RootJsonFormat[DocRevision] {
    def write(d: DocRevision) = if (d.rev != null) JsString(d.rev) else JsNull

    def read(value: JsValue) = value match {
      case JsString(s) => DocRevision(s)
      case JsNull      => DocRevision.empty
      case _           => deserializationError("doc revision malformed")
    }
  }
}

protected[core] object DocInfo extends DefaultJsonProtocol {

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

  implicit val serdes = jsonFormat2(DocInfo.apply)
}

object BulkEntityResult extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat4(BulkEntityResult.apply)
}
