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

import com.microsoft.azure.cosmosdb.{
  DataType,
  HashIndex,
  IndexKind,
  RangeIndex,
  ExcludedPath => JExcludedPath,
  IncludedPath => JIncludedPath,
  Index => JIndex,
  IndexingPolicy => JIndexingPolicy
}

import scala.collection.JavaConverters._

/**
 * Scala based IndexingPolicy type which maps to java based IndexingPolicy. This is done for 2 reasons
 *
 *  - Simplify constructing policy instance
 *  - Enable custom comparison between existing policy and desired policy as policy instances
 *    obtained from CosmosDB have extra index type configured per included path. Hence the comparison
 *    needs to be customized
 *
 */
case class IndexingPolicy(includedPaths: Set[IncludedPath],
                          excludedPaths: Set[ExcludedPath] = Set(ExcludedPath("/*"))) {

  def asJava(): JIndexingPolicy = {
    val policy = new JIndexingPolicy()
    policy.setIncludedPaths(includedPaths.map(_.asJava()).asJava)
    policy.setExcludedPaths(excludedPaths.map(_.asJava()).asJava)
    policy
  }
}

object IndexingPolicy {
  def apply(policy: JIndexingPolicy): IndexingPolicy =
    IndexingPolicy(
      policy.getIncludedPaths.asScala.map(IncludedPath(_)).toSet,
      policy.getExcludedPaths.asScala.map(ExcludedPath(_)).toSet)

  /**
   * IndexingPolicy fetched from CosmosDB contains extra entries. So need to check
   * that at least what we expect is present
   */
  def isSame(expected: IndexingPolicy, current: IndexingPolicy): Boolean = {
    epaths(expected.excludedPaths) == epaths(current.excludedPaths) &&
    ipaths(expected.includedPaths) == ipaths(current.includedPaths)
  }

  private def ipaths(included: Set[IncludedPath]) = included.map(_.path)

  //CosmosDB seems to add _etag by default in excluded path. So explicitly ignore that in comparison
  private def epaths(excluded: Set[ExcludedPath]) = excluded.map(_.path).filterNot(_.contains("_etag"))
}

case class IncludedPath(path: String, indexes: Set[Index]) {
  def asJava(): JIncludedPath = {
    val includedPath = new JIncludedPath()
    includedPath.setIndexes(indexes.map(_.asJava()).asJava)
    includedPath.setPath(path)
    includedPath
  }
}

object IncludedPath {
  def apply(ip: JIncludedPath): IncludedPath = IncludedPath(ip.getPath, ip.getIndexes.asScala.map(Index(_)).toSet)

  def apply(path: String, index: Index): IncludedPath = IncludedPath(path, Set(index))
}

case class ExcludedPath(path: String) {
  def asJava(): JExcludedPath = {
    val excludedPath = new JExcludedPath()
    excludedPath.setPath(path)
    excludedPath
  }
}

object ExcludedPath {
  def apply(ep: JExcludedPath): ExcludedPath = ExcludedPath(ep.getPath)
}

case class Index(kind: IndexKind, dataType: DataType, precision: Int) {
  def asJava(): JIndex = kind match {
    case IndexKind.Hash  => JIndex.Hash(dataType, precision)
    case IndexKind.Range => JIndex.Range(dataType, precision)
    case _               => throw new RuntimeException(s"Unsupported kind $kind")
  }
}

object Index {
  def apply(index: JIndex): Index = index match {
    case i: HashIndex  => Index(i.getKind, i.getDataType, i.getPrecision)
    case i: RangeIndex => Index(i.getKind, i.getDataType, i.getPrecision)
    case _             => throw new RuntimeException(s"Unsupported kind $index")
  }
}
