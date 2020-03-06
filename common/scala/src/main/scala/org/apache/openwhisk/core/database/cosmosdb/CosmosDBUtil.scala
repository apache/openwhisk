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

package org.apache.openwhisk.core.database.cosmosdb

import com.microsoft.azure.cosmosdb.internal.Constants.Properties.{AGGREGATE, E_TAG, ID, SELF_LINK}
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBConstants._
import org.apache.openwhisk.core.database.StoreUtils.transform
import spray.json.{JsObject, JsString}

private[cosmosdb] object CosmosDBConstants {

  /**
   * Stores the computed properties required for view related queries
   */
  val computed: String = "_c"

  val alias: String = "view"

  val cid: String = ID

  val etag: String = E_TAG

  val aggregate: String = AGGREGATE

  val selfLink: String = SELF_LINK

  /**
   * Records the clusterId which performed changed in any document. This can vary over
   * lifetime of a document as different clusters may change the same document at different times
   */
  val clusterId: String = "_clusterId"

  /**
   * Property indicating that document has been marked as deleted with ttl
   */
  val deleted: String = "_deleted"
}

private[cosmosdb] trait CosmosDBUtil {

  /**
   * Name of `id` field as used in WhiskDocument
   */
  val _id: String = "_id"

  /**
   * Name of revision field as used in WhiskDocument
   */
  val _rev: String = "_rev"

  /**
   * Prepares the json like select clause
   * {{{
   *   Seq("a", "b", "c.d.e") =>
   *   { "a" : r['a'], "b" : r['b'], "c" : { "d" : { "e" : r['c']['d']['e']}}, "id" : r['id']} AS view
   * }}}
   * Here it uses {{{r['keyName']}}} notation to avoid issues around using reserved words as field name
   */
  def prepareFieldClause(fields: Set[String]): String = {
    val json = (fields + cid)
      .map { field =>
        val split = field.split('.')

        val selector = "r" + split.mkString("['", "']['", "']")
        val prefix = split.map(k => s""""$k":""").mkString("{")
        val suffix = split.drop(1).map(_ => "}").mkString

        prefix + selector + suffix
      }
      .mkString("{", ",", "}")
    s"$json AS $alias"
  }

  /**
   * CosmosDB id considers '/', '\' , '?' and '#' as invalid. EntityNames can include '/' so
   * that need to be escaped. For that we use '|' as the replacement char
   */
  def escapeId(id: String): String = {
    require(!id.contains("|"), s"Id [$id] should not contain '|'")
    id.replace("/", "|")
  }

  def unescapeId(id: String): String = {
    require(!id.contains("/"), s"Escaped Id [$id] should not contain '/'")
    id.replace("|", "/")
  }

  def toWhiskJsonDoc(js: JsObject, id: String, etag: Option[JsString]): JsObject = {
    val fieldsToAdd = Seq((_id, Some(JsString(unescapeId(id)))), (_rev, etag))
    transform(stripInternalFields(js), fieldsToAdd, Seq.empty)
  }

  private def stripInternalFields(js: JsObject) = {
    //Strip out all field name starting with '_' which are considered as db specific internal fields
    JsObject(js.fields.filter { case (k, _) => !k.startsWith("_") && k != cid })
  }

}

private[cosmosdb] object CosmosDBUtil extends CosmosDBUtil
