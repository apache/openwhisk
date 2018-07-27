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

package whisk.core.database.mongodb

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import spray.json._
import spray.json.DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class MongoDBStoreUtilTests extends FlatSpec with Matchers {
  import whisk.core.database.mongodb.MongoDBArtifactStore._

  behavior of "StringPurge"

  it should "escape '$' character if it is the first char of a field name" in {
    val doc = List(Map("$a" -> "$aaa"), Map("b$" -> "bbb", "b" -> "b$b", "$b" -> "$bbb"), Map("$c" -> "cc$"))
    doc.toJson.compactPrint.escapeDollar shouldBe """[{"_mark_of_dollar_a":"$aaa"},{"b$":"bbb","b":"b$b","_mark_of_dollar_b":"$bbb"},{"_mark_of_dollar_c":"cc$"}]"""
  }

  it should "recover '$' character from the escapeDollar method" in {
    val doc = List(Map("$a" -> "$aaa"), Map("b$" -> "bbb", "b" -> "b$b", "$b" -> "$bbb"), Map("$c" -> "cc$"))
    val purgedStr = doc.toJson.compactPrint.escapeDollar
    purgedStr shouldBe """[{"_mark_of_dollar_a":"$aaa"},{"b$":"bbb","b":"b$b","_mark_of_dollar_b":"$bbb"},{"_mark_of_dollar_c":"cc$"}]"""

    purgedStr.recoverDollar.parseJson.convertTo[List[Map[String, String]]] shouldBe doc
  }
}
