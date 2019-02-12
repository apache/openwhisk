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

package org.apache.openwhisk.spi

import com.typesafe.config.ConfigFactory

/** Marker trait to mark an Spi */
trait Spi

trait SpiClassResolver {

  /** Resolves the implementation for a given type */
  def getClassNameForType[T: Manifest]: String
}

object SpiLoader {

  /**
   * Instantiates an object of the given type.
   *
   * The ClassName to load is resolved via the SpiClassResolver in scode, which defaults to
   * a TypesafeConfig based resolver.
   */
  def get[A <: Spi: Manifest](implicit resolver: SpiClassResolver = TypesafeConfigClassResolver): A = {
    val clazz = Class.forName(resolver.getClassNameForType[A] + "$")
    clazz.getField("MODULE$").get(clazz).asInstanceOf[A]
  }
}

/** Lookup the classname for the SPI impl based on a key in the provided Config */
object TypesafeConfigClassResolver extends SpiClassResolver {
  private val config = ConfigFactory.load()

  override def getClassNameForType[T: Manifest]: String =
    config.getString("whisk.spi." + manifest[T].runtimeClass.getSimpleName)
}
