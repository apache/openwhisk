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

package whisk.common

import java.security.SecureRandom

import org.apache.commons.codec.binary.Hex

object RandomString {
  private val generator = new ThreadLocal[SecureRandom] {
    override def initialValue() = new SecureRandom()
  }

  val lowercase: IndexedSeq[Char] = 'a' to 'z'
  val uppercase: IndexedSeq[Char] = 'A' to 'Z'
  val digits: IndexedSeq[Char] = '0' to '9'
  val alphanumeric: IndexedSeq[Char] = lowercase ++ uppercase ++ digits

  /**
   * Generates a random string of hexadecimal characters and the given length.
   *
   * Vastly optimized for raw speed over the other generate methods. For that optimization, the length of the string
   * needs to be divisible by 2 (4 bits make one hex character and a byte consists of 8 bits). If the number is not
   * divisible by two, the outcome string will be one character shorter than expected.
   *
   * @param length length of the string to generate
   * @return a random string
   */
  def generateHexadecimal(length: Int): String = {
    val bytes = Array.ofDim[Byte](length / 2)
    generator.get().nextBytes(bytes)
    Hex.encodeHexString(bytes)
  }

  /**
   * Generates a random string of the given character range and the given length.
   *
   * @param characters the characters to build the target string of
   * @param length length of the target string
   * @return a random string
   */
  def generate(characters: IndexedSeq[Char])(length: Int): String = {
    val random = generator.get()
    Stream.continually(characters(random.nextInt(characters.length))).take(length).mkString
  }
}
