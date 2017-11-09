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

package whisk.core.entity.test

import org.scalatest.Matchers
import org.scalatest.Suite

import common.StreamLogging
import common.WskActorSystem
import whisk.core.WhiskConfig
import whisk.core.entity._
import whisk.core.entity.ArgNormalizer.trim
import whisk.core.entity.ExecManifest._

trait ExecHelpers extends Matchers with WskActorSystem with StreamLogging {
  self: Suite =>

  private val config = new WhiskConfig(ExecManifest.requiredProperties)
  ExecManifest.initialize(config) should be a 'success

  protected val NODEJS = "nodejs"
  protected val NODEJS6 = "nodejs:6"
  protected val SWIFT = "swift"
  protected val SWIFT3 = "swift:3"

  protected def imagename(name: String) =
    ExecManifest.ImageName(s"${name}action".replace(":", ""), Some("openwhisk"), Some("latest"))

  protected def js(code: String, main: Option[String] = None) = {
    CodeExecAsString(RuntimeManifest(NODEJS, imagename(NODEJS), deprecated = Some(true)), trim(code), main.map(_.trim))
  }

  protected def js6(code: String, main: Option[String] = None) = {
    CodeExecAsString(
      RuntimeManifest(NODEJS6, imagename(NODEJS6), default = Some(true), deprecated = Some(false)),
      trim(code),
      main.map(_.trim))
  }

  protected def jsDefault(code: String, main: Option[String] = None) = {
    js6(code, main)
  }

  protected def js6MetaData(code: String, main: Option[String] = None) = {
    CodeExecMetaDataAsString(
      RuntimeManifest(NODEJS6, imagename(NODEJS6), default = Some(true), deprecated = Some(false)))
  }

  protected def jsDefaultMetaData(code: String, main: Option[String] = None) = {
    js6MetaData(code, main)
  }

  protected def swift(code: String, main: Option[String] = None) = {
    CodeExecAsString(RuntimeManifest(SWIFT, imagename(SWIFT), deprecated = Some(true)), trim(code), main.map(_.trim))
  }

  protected def swift3(code: String, main: Option[String] = None) = {
    val default = ExecManifest.runtimesManifest.resolveDefaultRuntime(SWIFT3).flatMap(_.default)
    CodeExecAsString(
      RuntimeManifest(SWIFT3, imagename(SWIFT3), default = default, deprecated = Some(false)),
      trim(code),
      main.map(_.trim))
  }

  protected def sequence(components: Vector[FullyQualifiedEntityName]) = SequenceExec(components)

  protected def bb(image: String) = BlackBoxExec(ExecManifest.ImageName(trim(image)), None, None, false)

  protected def bb(image: String, code: String, main: Option[String] = None) = {
    BlackBoxExec(ExecManifest.ImageName(trim(image)), Some(trim(code)).filter(_.nonEmpty), main, false)
  }
}
