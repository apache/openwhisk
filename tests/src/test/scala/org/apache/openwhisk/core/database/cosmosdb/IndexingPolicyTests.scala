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

import com.microsoft.azure.cosmosdb.DataType.String
import com.microsoft.azure.cosmosdb.IndexKind.Range
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class IndexingPolicyTests extends FlatSpec with Matchers {
  behavior of "IndexingPolicy"

  it should "match same instance" in {
    val policy =
      IndexingPolicy(includedPaths = Set(IncludedPath("foo", Index(Range, String, -1))))
    IndexingPolicy.isSame(policy, policy) shouldBe true
  }

  it should "not match when same path are different" in {
    val policy =
      IndexingPolicy(
        includedPaths =
          Set(IncludedPath("foo", Index(Range, String, -1)), IncludedPath("bar", Index(Range, String, -1))))

    val policy2 =
      IndexingPolicy(
        includedPaths = Set(
          IncludedPath("foo2", Index(Range, String, -1)),
          IncludedPath("bar", Set(Index(Range, String, -1), Index(Range, String, -1)))))

    IndexingPolicy.isSame(policy, policy2) shouldBe false
  }

  it should "convert and match java IndexingPolicy" in {
    val policy =
      IndexingPolicy(
        includedPaths = Set(
          IncludedPath("foo", Index(Range, String, -1)),
          IncludedPath("bar", Set(Index(Range, String, -1), Index(Range, String, -1)))))

    val jpolicy = policy.asJava()
    val policy2 = IndexingPolicy(jpolicy)

    policy shouldBe policy2
  }
}
