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

import java.nio.charset.StandardCharsets

import com.typesafe.config.ConfigValue
import pureconfig._
import pureconfig.generic.auto._
import spray.json._
import ByteSize.formatError

object SizeUnits extends Enumeration {

  sealed abstract class Unit() {
    def toBytes(n: Long): Long
    def toKBytes(n: Long): Long
    def toMBytes(n: Long): Long
    def toGBytes(n: Long): Long
  }

  case object BYTE extends Unit {
    def toBytes(n: Long): Long = n
    def toKBytes(n: Long): Long = n / 1024
    def toMBytes(n: Long): Long = n / 1024 / 1024
    def toGBytes(n: Long): Long = n / 1024 / 1024 / 1024
  }
  case object KB extends Unit {
    def toBytes(n: Long): Long = n * 1024
    def toKBytes(n: Long): Long = n
    def toMBytes(n: Long): Long = n / 1024
    def toGBytes(n: Long): Long = n / 1024 / 1024

  }
  case object MB extends Unit {
    def toBytes(n: Long): Long = n * 1024 * 1024
    def toKBytes(n: Long): Long = n * 1024
    def toMBytes(n: Long): Long = n
    def toGBytes(n: Long): Long = n / 1024
  }
  case object GB extends Unit {
    def toBytes(n: Long): Long = n * 1024 * 1024 * 1024
    def toKBytes(n: Long): Long = n * 1024 * 1024
    def toMBytes(n: Long): Long = n * 1024
    def toGBytes(n: Long): Long = n
  }
}

case class ByteSize(size: Long, unit: SizeUnits.Unit) extends Ordered[ByteSize] {

  require(size >= 0, "a negative size of an object is not allowed.")

  def toBytes = unit.toBytes(size)
  def toKB = unit.toKBytes(size)
  def toMB = unit.toMBytes(size)

  def +(other: ByteSize): ByteSize = {
    val commonUnit = SizeUnits.BYTE
    val commonSize = other.toBytes + toBytes
    ByteSize(commonSize, commonUnit)
  }

  def -(other: ByteSize): ByteSize = {
    val commonUnit = SizeUnits.BYTE
    val commonSize = toBytes - other.toBytes
    ByteSize(commonSize, commonUnit)
  }

  def *(other: Int): ByteSize = {
    ByteSize(toBytes * other, SizeUnits.BYTE)
  }

  def /(other: ByteSize): Double = {
    // Without throwing the exception the result would be `Infinity` here
    if (other.toBytes == 0) {
      throw new ArithmeticException
    } else {
      (1.0 * toBytes) / (1.0 * other.toBytes)
    }
  }

  def /(other: Int): ByteSize = {
    ByteSize(toBytes / other, SizeUnits.BYTE)
  }

  def compare(other: ByteSize) = toBytes compare other.toBytes

  override def equals(that: Any): Boolean = that match {
    case t: ByteSize => compareTo(t) == 0
    case _           => false
  }

  override def toString = {
    unit match {
      case SizeUnits.BYTE => s"$size B"
      case SizeUnits.KB   => s"$size KB"
      case SizeUnits.MB   => s"$size MB"
      case SizeUnits.GB   => s"$size GB"
    }
  }
}

object ByteSize {
  private val regex = """(?i)\s?(\d+)\s?(GB|MB|KB|B|G|M|K)\s?""".r.pattern
  protected[entity] val formatError = """Size Unit not supported. Only "B", "K[B]", "M[B]" and "G[B]" are supported."""

  def fromString(sizeString: String): ByteSize = {
    val matcher = regex.matcher(sizeString)
    if (matcher.matches()) {
      val size = matcher.group(1).toLong
      val unit = matcher.group(2).charAt(0).toUpper match {
        case 'B' => SizeUnits.BYTE
        case 'K' => SizeUnits.KB
        case 'M' => SizeUnits.MB
        case 'G' => SizeUnits.GB
      }

      ByteSize(size, unit)
    } else {
      throw new IllegalArgumentException(formatError)
    }
  }
}

object size {
  implicit class SizeInt(n: Int) extends SizeConversion {
    def sizeIn(unit: SizeUnits.Unit): ByteSize = ByteSize(n, unit)
  }

  implicit class SizeLong(n: Long) extends SizeConversion {
    def sizeIn(unit: SizeUnits.Unit): ByteSize = ByteSize(n, unit)
  }

  implicit class SizeString(n: String) extends SizeConversion {
    def sizeIn(unit: SizeUnits.Unit): ByteSize = ByteSize(n.getBytes(StandardCharsets.UTF_8).length, unit)
  }

  implicit class SizeOptionString(n: Option[String]) extends SizeConversion {
    def sizeIn(unit: SizeUnits.Unit): ByteSize =
      n map { s =>
        s.sizeIn(unit)
      } getOrElse {
        ByteSize(0, unit)
      }
  }

  // Creation of an intermediary Config object is necessary here, since "getBytes" is only part of that interface.
  implicit val pureconfigReader =
    ConfigReader[ConfigValue].map(v => ByteSize(v.atKey("key").getBytes("key"), SizeUnits.BYTE))

  protected[entity] implicit val serdes = new RootJsonFormat[ByteSize] {
    def write(b: ByteSize) = JsString(b.toString)

    def read(value: JsValue): ByteSize = value match {
      case JsString(s) => ByteSize.fromString(s)
      case _           => deserializationError(formatError)
    }
  }
}

trait SizeConversion {
  def B = sizeIn(SizeUnits.BYTE)
  def KB = sizeIn(SizeUnits.KB)
  def MB = sizeIn(SizeUnits.MB)
  def GB: ByteSize = sizeIn(SizeUnits.GB)
  def bytes = B
  def kilobytes = KB
  def megabytes = MB
  def gigabytes: ByteSize = GB

  def sizeInBytes = sizeIn(SizeUnits.BYTE)

  def sizeIn(unit: SizeUnits.Unit): ByteSize
}
