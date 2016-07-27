/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity.test

import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import whisk.core.entity.size.SizeInt
import whisk.core.entity.ByteSize

@RunWith(classOf[JUnitRunner])
class SizeTests extends FlatSpec with Matchers {

    behavior of "Size Entity"

    // Comparing
    it should "1 Byte smaller than 1 KB smaller than 1 MB" in {
        val oneByte = 1 B
        val oneKB = 1 KB
        val oneMB = 1 MB

        oneByte < oneKB should be(true)
        oneKB < oneMB should be(true)
    }

    it should "3 Bytes smaller than 2 KB smaller than 1 MB" in {
        val myBytes = 3 B
        val myKBs = 2 KB
        val myMBs = 1 MB

        myBytes < myKBs should be(true)
        myKBs < myMBs should be(true)
    }

    it should "1 MB greater than 1 KB greater than 1 Byte" in {
        val oneByte = 1 B
        val oneKB = 1 KB
        val oneMB = 1 MB

        oneMB > oneKB should be(true)
        oneKB > oneByte should be(true)
    }

    it should "1 MB == 1024 KB == 1048576 B" in {
        val myBytes = 1048576 B
        val myKBs = 1024 KB
        val myMBs = 1 MB

        myBytes equals (myKBs)
        myKBs equals (myMBs)
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

    // Conversions
    it should "1024 B to KB = 1" in {
        (1024 B).toKB should be(1)
    }

    it should "1048576 B to MB = 1" in {
        (1048576 B).toMB should be(1)
    }

    it should "1 KB to B = 1024" in {
        (1 KB).toBytes should be(1024)
    }

    it should "1024 KB to MB = 1" in {
        (1024 KB).toMB should be(1)
    }

    it should "1 MB to B = 1048576" in {
        (1 MB).toBytes should be(1048576)
    }

    it should "1 MB to KB = 1024" in {
        (1 MB).toKB should be(1024)
    }

    // Create ObjectSize from String
    it should "create ObjectSize from String 3B" in {
        val fromString = ByteSize.fromString("3B")
        fromString equals (3 B)
    }

    it should "create ObjectSize from String 7K" in {
        val fromString = ByteSize.fromString("7K")
        fromString equals (7 KB)
    }

    it should "create ObjectSize from String 120M" in {
        val fromString = ByteSize.fromString("120M")
        fromString equals (120 MB)
    }

    it should "throw error on creating ObjectSize from String 120A" in {
        the[IllegalArgumentException] thrownBy {
            ByteSize.fromString("120A")
        } should have message """Size Unit not supported. Only "B", "K" and "M" are supported."""
    }

    it should "throw error on creating ByteSize object with negative size" in {
        the[IllegalArgumentException] thrownBy {
            -130 MB
        } should have message "requirement failed: a negative size of an object is not allowed."
    }
}
