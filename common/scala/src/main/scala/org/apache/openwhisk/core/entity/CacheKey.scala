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

import spray.json.DefaultJsonProtocol

class UnsupportedCacheKeyTypeException(msg: String) extends Exception(msg)

/**
 * A key that is used to store an entity on the cache.
 *
 * @param mainId The main part for the key to be used. For example this is the id of a document.
 * @param secondaryId A second part of the key. For example the revision of an entity. This part
 * of the key will not be written to the logs.
 */
case class CacheKey(mainId: String, secondaryId: Option[String]) {
  override def toString() = {
    s"CacheKey($mainId)"
  }
}

object CacheKey extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(CacheKey.apply)

  def apply(key: Any): CacheKey = {
    key match {
      case e: EntityName                 => CacheKey(e.asString, None)
      case a: BasicAuthenticationAuthKey => CacheKey(a.uuid.asString, Some(a.key.asString))
      case d: DocInfo => {
        val revision = if (d.rev.empty) None else Some(d.rev.asString)
        CacheKey(d.id.asString, revision)
      }
      case w: WhiskEntity => CacheKey(w.docid.asDocInfo)
      case s: String      => CacheKey(s, None)
      case others => {
        throw new UnsupportedCacheKeyTypeException(s"Unable to apply the entity ${others.getClass} on CacheKey.")
      }
    }
  }
}
