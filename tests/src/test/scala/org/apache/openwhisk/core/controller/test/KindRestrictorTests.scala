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

package org.apache.openwhisk.core.controller.test

import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import org.apache.openwhisk.core.entitlement.KindRestrictor
import org.apache.openwhisk.core.entity._

/**
 * Tests authorization handler which guards resources.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 */
@RunWith(classOf[JUnitRunner])
class KindRestrictorTests extends FlatSpec with Matchers with StreamLogging {

  behavior of "Kind Restrictor"

  val allowedKinds = Set("nodejs:14", "python")
  val disallowedKinds = Set("golang", "blackbox")
  val allKinds = allowedKinds ++ disallowedKinds

  it should "grant subject access to all kinds when no limits exist and no white list is defined" in {
    val subject = WhiskAuthHelpers.newIdentity()
    val kr = KindRestrictor()
    allKinds.foreach(k => kr.check(subject, k) shouldBe true)
  }

  it should "grant subject access to any kinds if limit is the empty set" in {
    val subject = WhiskAuthHelpers.newIdentity().copy(limits = UserLimits(allowedKinds = Some(Set.empty)))
    val kr = KindRestrictor()
    allKinds.foreach(k => kr.check(subject, k) shouldBe true)
  }

  it should "grant subject access to any kinds if white list is the empty set" in {
    val subject = WhiskAuthHelpers.newIdentity()
    val kr = KindRestrictor(Set[String]())
    allKinds.foreach(k => kr.check(subject, k) shouldBe true)
  }

  it should "grant subject access only to subject-limited kinds" in {
    val subject = WhiskAuthHelpers.newIdentity().copy(limits = UserLimits(allowedKinds = Some(allowedKinds)))
    val kr = KindRestrictor()
    allowedKinds.foreach(k => kr.check(subject, k) shouldBe true)
    disallowedKinds.foreach(k => kr.check(subject, k) shouldBe false)
  }

  it should "grant subject access to white listed kinds when no limits exist" in {
    val subject = WhiskAuthHelpers.newIdentity()
    val kr = KindRestrictor(allowedKinds)
    allowedKinds.foreach(k => kr.check(subject, k) shouldBe true)
    disallowedKinds.foreach(k => kr.check(subject, k) shouldBe false)
  }

  it should "grant subject access both explicitly limited kinds and default whitelisted kinds" in {
    val explicitKind = allowedKinds.head
    val subject = WhiskAuthHelpers.newIdentity().copy(limits = UserLimits(allowedKinds = Some(Set(explicitKind))))
    val kr = KindRestrictor(allowedKinds.tail)
    allKinds.foreach(k => kr.check(subject, k) shouldBe allowedKinds.contains(k))
  }

}
