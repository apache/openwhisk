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

package org.apache.openwhisk.core.scheduler.queue.test

import org.apache.openwhisk.core.scheduler.queue.{DurationCheckResult, ElasticSearchDurationCheckResultFormat}
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import spray.json._

@RunWith(classOf[JUnitRunner])
class ElasticSearchDurationCheckResultFormatTest extends FlatSpec with Matchers with ScalaFutures {
  behavior of "ElasticSearchDurationCheckResultFormatTest"

  val serde = new ElasticSearchDurationCheckResultFormat()

  it should "serialize the data correctly" in {
    val normalData = """{
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "aggregations": {
              "filterAggregation": {
                  "averageAggregation": {
                      "value": 14
                  },
                  "doc_count": 3
              }
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 3
          },
          "timed_out": false,
          "took": 2
      }"""

    val bindingData = """{
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "aggregations": {
              "averageAggregation": {
                  "value": 12
              }
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 2
          },
          "timed_out": false,
          "took": 0
      }"""

    val expected1 = DurationCheckResult(Some(14), 3, 2)
    val expected2 = DurationCheckResult(Some(12), 2, 0)
    val result1 = serde.read(normalData.parseJson)
    val result2 = serde.read(bindingData.parseJson)

    result1 shouldBe expected1
    result2 shouldBe expected2
  }

  // Since the write method is not being used, this test is meaningless but added just for the duality.
  it should "deserialize the data correctly" in {
    val data = DurationCheckResult(Some(14), 3, 2)
    val expected =
      """[14, 3, 2]
        |
        |""".stripMargin
    val result = serde.write(data)

    result shouldBe expected.parseJson
  }

  it should "throw an exception when data is not in the expected format" in {
    val malformedData = """{
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "averageAggregation": {
              "value": 14
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 3
          },
          "timed_out": false,
          "took": 2
      }"""

    assertThrows[DeserializationException] {
      serde.read(malformedData.parseJson)
    }
  }
}
