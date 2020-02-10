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

package org.apache.openwhisk.common

import java.time.{Instant}
import spray.json._
import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TransactionIdTests extends FlatSpec with Matchers with StreamLogging {

  behavior of "TransactionId deserialization"

  it should "deserialize with parent tid" in {

    val json = """["ctid1",1600000000000,["ptid1",1500000000000]]""".stripMargin.parseJson

    val pnow = Instant.ofEpochMilli(1500000000000L)
    val cnow = Instant.ofEpochMilli(1600000000000L)

    val ptid = TransactionId(TransactionMetadata("ptid1", pnow))
    val ctid = TransactionId(TransactionMetadata("ctid1", cnow, parent = Some(ptid.meta)))

    val transactionId = TransactionId.serdes.read(json)
    transactionId shouldBe ctid
  }

  it should "deserialize with parent tid and extraLogging parameter" in {

    val json = """["ctid1",1600000000000,true,["ptid1",1500000000000,true]]""".stripMargin.parseJson

    val pnow = Instant.ofEpochMilli(1500000000000L)
    val cnow = Instant.ofEpochMilli(1600000000000L)

    val ptid = TransactionId(TransactionMetadata("ptid1", pnow, extraLogging = true))
    val ctid = TransactionId(TransactionMetadata("ctid1", cnow, extraLogging = true, parent = Some(ptid.meta)))

    val transactionId = TransactionId.serdes.read(json)
    transactionId shouldBe ctid
  }

  it should "deserialize without parent tid" in {

    val json = """["ctid1",1600000000000]""".stripMargin.parseJson

    val cnow = Instant.ofEpochMilli(1600000000000L)
    val ctid = TransactionId(TransactionMetadata("ctid1", cnow))

    val transactionId = TransactionId.serdes.read(json)
    transactionId shouldBe ctid
  }

  behavior of "TransactionId serialization"

  it should "serialize with parent tid" in {

    val pnow = Instant.ofEpochMilli(1500000000000L)
    val cnow = Instant.ofEpochMilli(1600000000000L)

    val ptid = TransactionId(TransactionMetadata("ptid1", pnow))
    val ctid = TransactionId(TransactionMetadata("ctid1", cnow, parent = Some(ptid.meta)))

    val js = TransactionId.serdes.write(ctid)

    val js2 = """["ctid1",1600000000000,["ptid1",1500000000000]]""".stripMargin.parseJson
    js shouldBe js2
  }

  it should "serialize with parent tid and extraLogging parameter" in {

    val pnow = Instant.ofEpochMilli(1500000000000L)
    val cnow = Instant.ofEpochMilli(1600000000000L)

    val ptid = TransactionId(TransactionMetadata("ptid1", pnow, extraLogging = true))
    val ctid = TransactionId(TransactionMetadata("ctid1", cnow, extraLogging = true, parent = Some(ptid.meta)))

    val js = TransactionId.serdes.write(ctid)

    val js2 = """["ctid1",1600000000000,true,["ptid1",1500000000000,true]]""".stripMargin.parseJson
    js shouldBe js2
  }

  it should "serialize without parent tid" in {

    val cnow = Instant.ofEpochMilli(1600000000000L)
    val ctid = TransactionId(TransactionMetadata("ctid1", cnow))

    val js = TransactionId.serdes.write(ctid)
    val js2 = """["ctid1",1600000000000]""".stripMargin.parseJson
    js shouldBe js2
  }

}
