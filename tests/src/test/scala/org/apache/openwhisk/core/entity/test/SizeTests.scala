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

package org.apache.openwhisk.core.entity.test

import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.core.entity.ByteSize

@RunWith(classOf[JUnitRunner])
class SizeTests extends FlatSpec with Matchers {

  behavior of "Size Entity"

  // Comparing
  it should "1 Byte smaller than 1 KB smaller than 1 MB" in {
    val oneByte = 1 B
    val oneKB = 1 KB
    val oneMB = 1 MB

    oneByte should be < oneKB
    oneKB should be < oneMB
  }

  it should "3 Bytes smaller than 2 KB smaller than 1 MB" in {
    val myBytes = 3 B
    val myKBs = 2 KB
    val myMBs = 1 MB

    myBytes should be < myKBs
    myKBs should be < myMBs
  }

  it should "1 MB greater than 1 KB greater than 1 Byte" in {
    val oneByte = 1 B
    val oneKB = 1 KB
    val oneMB = 1 MB

    oneMB should be > oneKB
    oneKB should be > oneByte
  }

  it should "1 MB == 1024 KB == 1048576 B" in {
    val myBytes = (1 << 20) B
    val myKBs = 1024 KB
    val myMBs = 1 MB

    myBytes should equal(myKBs)
    myKBs should equal(myMBs)
  }

  // Addition
  it should "1 Byte + 1 KB = 1025 Bytes" in {
    1.B + 1.KB should be(1025 B)
  }

  it should "1 MB + 1 MB = 2 MB" in {
    (1.MB + 1.MB).toBytes should be(2.MB.toBytes)
  }

  // Subtraction
  it should "1 KB - 1B = 1023 Bytes" in {
    1.KB - 1.B should be(1023 B)
  }

  it should "1 MB - 1 MB = 0 MB" in {
    1.MB - 1.MB should be(0 B)
  }

  it should "throw an exception if subtraction leads to a negative size" in {
    an[IllegalArgumentException] should be thrownBy {
      0.B - 1.B
    }
  }

  // Multiplication
  it should "2 B * 10 = 20 B" in {
    2.B * 10 should be(20.B)
  }

  it should "40 MB * 2 = 80 MB" in {
    40.MB * 2 should be(80.MB)
  }

  // Division
  it should "5 Byte / 2 Byte = 2.5" in {
    5.B / 2.B should be(2.5)
  }

  it should "1 KB / 512 Byte = 2" in {
    1.KB / 512.B should be(2)
  }

  it should "throw an exception if division is through 0 byte" in {
    an[ArithmeticException] should be thrownBy {
      1.MB / 0.B
    }
  }

  it should "5 Byte / 2 = 2 Byte" in {
    5.B / 2 should be(2.B)
  }

  it should "1 MB / 512 = 2 Byte" in {
    1.MB / 512 should be(2.KB)
  }

  it should "not go into integer overflow for a few GB" in {
    4096.MB / 2 should be(2048.MB)
  }

  it should "throw an exception if division is through 0" in {
    an[ArithmeticException] should be thrownBy {
      1.MB / 0
    }
  }

  // Conversions
  it should "1024 B to KB = 1" in {
    (1024 B).toKB should be(1)
  }

  it should "1048576 B to MB = 1" in {
    ((1 << 20) B).toMB should be(1)
  }

  it should "1 KB to B = 1024" in {
    (1 KB).toBytes should be(1024)
  }

  it should "1024 KB to MB = 1" in {
    (1024 KB).toMB should be(1)
  }

  it should "1 MB to B = 1048576" in {
    (1 MB).toBytes should be(1 << 20)
  }

  it should "1 MB to KB = 1024" in {
    (1 MB).toKB should be(1024)
  }

  // Create ObjectSize from String
  it should "create ObjectSize from String 3B" in {
    ByteSize.fromString("3b") should be(3 B)
    ByteSize.fromString("3B") should be(3 B)
    ByteSize.fromString("3 b") should be(3 B)
    ByteSize.fromString("3 B") should be(3 B)
  }

  it should "create ObjectSize from String 7K" in {
    ByteSize.fromString("7k") should be(7 KB)
    ByteSize.fromString("7K") should be(7 KB)
    ByteSize.fromString("7KB") should be(7 KB)
    ByteSize.fromString("7kB") should be(7 KB)
    ByteSize.fromString("7kb") should be(7 KB)
    ByteSize.fromString("7 k") should be(7 KB)
    ByteSize.fromString("7 K") should be(7 KB)
    ByteSize.fromString("7 KB") should be(7 KB)
    ByteSize.fromString("7 kB") should be(7 KB)
    ByteSize.fromString("7 kb") should be(7 KB)
  }

  it should "create ObjectSize from String 120M" in {
    ByteSize.fromString("120m") should be(120 MB)
    ByteSize.fromString("120M") should be(120 MB)
    ByteSize.fromString("120MB") should be(120 MB)
    ByteSize.fromString("120mB") should be(120 MB)
    ByteSize.fromString("120mb") should be(120 MB)
    ByteSize.fromString("120 m") should be(120 MB)
    ByteSize.fromString("120 M") should be(120 MB)
    ByteSize.fromString("120 MB") should be(120 MB)
    ByteSize.fromString("120 mB") should be(120 MB)
    ByteSize.fromString("120 mb") should be(120 MB)
  }

  it should "read and write size as JSON" in {
    import org.apache.openwhisk.core.entity.size.serdes
    serdes.read(JsString("3b")) should be(3 B)
    serdes.write(3 B) should be(JsString("3 B"))
    a[DeserializationException] should be thrownBy (serdes.read(JsNumber(3)))
  }

  it should "throw error on creating ObjectSize from String 120A" in {
    the[IllegalArgumentException] thrownBy {
      ByteSize.fromString("120A")
    } should have message ByteSize.formatError
  }

  it should "throw error on creating ByteSize object with negative size" in {
    the[IllegalArgumentException] thrownBy {
      -130 MB
    } should have message "requirement failed: a negative size of an object is not allowed."
  }
}
