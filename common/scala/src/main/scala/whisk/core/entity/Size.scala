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

package whisk.core.entity

import java.nio.charset.StandardCharsets

object SizeUnits extends Enumeration {

  sealed abstract class Unit() {
    def toBytes(n: Long): Long
    def toKBytes(n: Long): Long
    def toMBytes(n: Long): Long
  }

  case object BYTE extends Unit {
    def toBytes(n: Long): Long = n
    def toKBytes(n: Long): Long = n / 1024
    def toMBytes(n: Long): Long = n / 1024 / 1024
  }
  case object KB extends Unit {
    def toBytes(n: Long): Long = n * 1024
    def toKBytes(n: Long): Long = n
    def toMBytes(n: Long): Long = n / 1024
  }
  case object MB extends Unit {
    def toBytes(n: Long): Long = n * 1024 * 1024
    def toKBytes(n: Long): Long = n * 1024
    def toMBytes(n: Long): Long = n
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

  def compare(other: ByteSize) = toBytes compare other.toBytes

  override def toString = {
    unit match {
      case SizeUnits.BYTE => s"$size B"
      case SizeUnits.KB   => s"$size KB"
      case SizeUnits.MB   => s"$size MB"
    }
  }
}

object ByteSize {
  def fromString(sizeString: String): ByteSize = {
    val unitprefix = sizeString.takeRight(1)
    val size = sizeString.dropRight(1).toLong

    val unit = unitprefix match {
      case "B" => SizeUnits.BYTE
      case "K" => SizeUnits.KB
      case "M" => SizeUnits.MB
      case _   => throw new IllegalArgumentException("""Size Unit not supported. Only "B", "K" and "M" are supported.""")
    }

    ByteSize(size, unit)
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
}

trait SizeConversion {
  def B = sizeIn(SizeUnits.BYTE)
  def KB = sizeIn(SizeUnits.KB)
  def MB = sizeIn(SizeUnits.MB)
  def bytes = B
  def kilobytes = KB
  def megabytes = MB

  def sizeInBytes = sizeIn(SizeUnits.BYTE)

  def sizeIn(unit: SizeUnits.Unit): ByteSize
}
