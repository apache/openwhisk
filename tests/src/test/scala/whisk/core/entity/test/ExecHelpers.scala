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

import org.scalatest.Matchers
import org.scalatest.Suite

import common.StreamLogging
import common.WskActorSystem
import whisk.core.WhiskConfig
import whisk.core.entity._
import whisk.core.entity.ArgNormalizer.trim
import whisk.core.entity.ExecManifest._

trait ExecHelpers
    extends Matchers
    with WskActorSystem
    with StreamLogging {
    self: Suite =>

    private val config = new WhiskConfig(ExecManifest.requiredProperties)
    ExecManifest.initialize(config) shouldBe true

    protected val NODEJS = "nodejs"
    protected val NODEJS6 = "nodejs:6"
    protected val SWIFT = "swift"
    protected val SWIFT3 = "swift:3"

    protected def js(code: String, main: Option[String] = None): Exec = CodeExecAsString(RuntimeManifest(NODEJS, deprecated = Some(true)), trim(code), main.map(_.trim))
    protected def js6(code: String, main: Option[String] = None): Exec = CodeExecAsString(RuntimeManifest(NODEJS6, default = Some(true), image = Some(Exec.imagename(NODEJS6))), trim(code), main.map(_.trim))
    protected def jsDefault(code: String, main: Option[String] = None) = {
        js6(code, main)
    }
    protected def swift(code: String, main: Option[String] = None): Exec = CodeExecAsString(RuntimeManifest(SWIFT, deprecated = Some(true)), trim(code), main.map(_.trim))
    protected def swift3(code: String, main: Option[String] = None): Exec = CodeExecAsString(RuntimeManifest(SWIFT3, default = Some(true), image = Some(Exec.imagename(SWIFT3))), trim(code), main.map(_.trim))
    protected def sequence(components: Vector[FullyQualifiedEntityName]): Exec = SequenceExec(components)
    protected def bb(image: String): Exec = BlackBoxExec(trim(image), None, None)
    protected def bb(image: String, code: String, main: Option[String] = None): Exec = BlackBoxExec(trim(image), Some(trim(code)).filter(_.nonEmpty), main)
}
