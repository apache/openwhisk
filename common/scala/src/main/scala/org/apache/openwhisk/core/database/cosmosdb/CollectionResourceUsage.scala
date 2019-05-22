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

import org.apache.commons.io.FileUtils
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.SizeUnits.KB

case class CollectionResourceUsage(documentsSize: Option[ByteSize],
                                   collectionSize: Option[ByteSize],
                                   documentsCount: Option[Long],
                                   indexingProgress: Option[Int],
                                   documentsSizeQuota: Option[ByteSize]) {
  def indexSize: Option[ByteSize] = {
    for {
      ds <- documentsSize
      cs <- collectionSize
    } yield cs - ds
  }

  def asString: String = {
    List(
      documentsSize.map(ds => s"documentSize: ${displaySize(ds)}"),
      indexSize.map(is => s"indexSize: ${displaySize(is)}"),
      documentsCount.map(dc => s"documentsCount: $dc"),
      documentsSizeQuota.map(dq => s"collectionSizeQuota: ${displaySize(dq)}")).flatten.mkString(",")
  }

  private def displaySize(b: ByteSize) = FileUtils.byteCountToDisplaySize(b.toBytes)
}

object CollectionResourceUsage {
  val quotaHeader = "x-ms-resource-quota"
  val usageHeader = "x-ms-resource-usage"
  val indexHeader = "x-ms-documentdb-collection-index-transformation-progress"

  def apply(responseHeaders: Map[String, String]): Option[CollectionResourceUsage] = {
    for {
      quota <- responseHeaders.get(quotaHeader).map(headerValueToMap)
      usage <- responseHeaders.get(usageHeader).map(headerValueToMap)
    } yield {
      CollectionResourceUsage(
        usage.get("documentsSize").map(_.toLong).map(ByteSize(_, KB)),
        usage.get("collectionSize").map(_.toLong).map(ByteSize(_, KB)),
        usage.get("documentsCount").map(_.toLong),
        responseHeaders.get(indexHeader).map(_.toInt),
        quota.get("collectionSize").map(_.toLong).map(ByteSize(_, KB)))
    }
  }

  private def headerValueToMap(value: String): Map[String, String] = {
    //storedProcedures=100;triggers=25;functions=25;documentsCount=-1;documentsSize=xxx;collectionSize=xxx
    val pairs = value.split("=|;").grouped(2)
    pairs.map { case Array(k, v) => k -> v }.toMap
  }
}
