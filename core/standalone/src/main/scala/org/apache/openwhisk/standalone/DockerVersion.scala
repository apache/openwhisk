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

package org.apache.openwhisk.standalone

import scala.util.control.NonFatal

case class DockerVersion(major: Int, minor: Int, patch: Int) extends Ordered[DockerVersion] {
  import scala.math.Ordered.orderingToOrdered
  def compare(that: DockerVersion): Int =
    (this.major, this.minor, this.patch) compare (that.major, that.minor, that.patch)

  override def toString = s"$major.$minor.$patch"
}

object DockerVersion {
  implicit val ord: Ordering[DockerVersion] = Ordering.by(unapply)
  private val pattern = ".*Docker version ([\\d.]+).*".r

  def apply(str: String): DockerVersion = {
    try {
      val parts = if (str != null && str.nonEmpty) str.split('.') else Array[String]()
      val major = if (parts.length >= 1) parts(0).toInt else 0
      val minor = if (parts.length >= 2) parts(1).toInt else 0
      val patch = if (parts.length >= 3) parts(2).toInt else 0
      DockerVersion(major, minor, patch)
    } catch {
      case NonFatal(_) => throw new IllegalArgumentException(s"bad docker version $str")
    }
  }

  def fromVersionCommand(str: String): DockerVersion = {
    val pattern(version) = str
    apply(version)
  }
}
