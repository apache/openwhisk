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

import spray.json.deserializationError
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import scala.util.Try

/**
 * State needed to implement semantic versioning.
 *
 * @see http://semver.org
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param (major, minor, patch) for the semantic version
 */
protected[core] class SemVer private (private val version: (Int, Int, Int)) extends AnyVal {

  protected[core] def major = version._1
  protected[core] def minor = version._2
  protected[core] def patch = version._3

  protected[core] def upMajor = SemVer(major + 1, minor, patch)
  protected[core] def upMinor = SemVer(major, minor + 1, patch)
  protected[core] def upPatch = SemVer(major, minor, patch + 1)

  protected[entity] def toJson = JsString(toString)
  override def toString = s"$major.$minor.$patch"
}

protected[core] object SemVer {
  protected[core] val REGEX = """(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"""

  /** Default semantic version */
  protected[core] def apply() = new SemVer(0, 0, 1)

  /**
   * Creates a semantic version.
   *
   * @param major the major >= 0
   * @param minor the minor >= 0
   * @param patch the patch >= 0
   * @return SemVer instance
   * @throws IllegalArgumentException if the parameters results in an invalid semantic version
   */
  protected[core] def apply(major: Int, minor: Int, patch: Int): SemVer = {
    if ((major == 0 && minor == 0 && patch == 0) || (major < 0 || minor < 0 || patch < 0)) {
      throw new IllegalArgumentException(s"bad semantic version $major.$minor.$patch must not be negative")
    } else new SemVer(major, minor, patch)
  }

  /**
   * Parses a string representation of a semantic version and creates
   * a instance of SemVer. If the string is not properly formatted, an
   * IllegalSemVer exception is thrown.
   *
   * @param str to parse to extract semantic version
   * @return SemVer instance
   * @thrown IllegalArgumentException if string is not a valid semantic version
   */
  protected[entity] def apply(str: String): SemVer = {
    try {
      val parts = if (str != null && str.nonEmpty) str.split('.') else Array[String]()
      val major = if (parts.size >= 1) parts(0).toInt else 0
      val minor = if (parts.size >= 2) parts(1).toInt else 0
      val patch = if (parts.size >= 3) parts(2).toInt else 0
      SemVer(major, minor, patch)
    } catch {
      case _: Throwable => throw new IllegalArgumentException(s"bad semantic version $str")
    }
  }

  implicit val serdes = new RootJsonFormat[SemVer] {
    def write(v: SemVer) = v.toJson

    def read(value: JsValue) =
      Try {
        val JsString(v) = value
        SemVer(v)
      } getOrElse deserializationError("semantic version malformed")
  }
}
