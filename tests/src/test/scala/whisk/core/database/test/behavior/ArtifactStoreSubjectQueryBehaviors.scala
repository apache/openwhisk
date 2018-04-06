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

package whisk.core.database.test.behavior

import org.scalatest.FlatSpec
import whisk.common.TransactionId
import whisk.core.entity._

trait ArtifactStoreSubjectQueryBehaviors extends ArtifactStoreBehaviorBase {
  this: FlatSpec =>

  behavior of s"${storeType}ArtifactStore query subjects"

  it should "find subject by namespace" in {
    implicit val tid: TransactionId = transid()
    val ak1 = AuthKey()
    val ak2 = AuthKey()
    val ns1 = aname()
    val ns2 = aname()
    val subs =
      Array(WhiskAuth(Subject(), Set(WhiskNamespace(ns1, ak1))), WhiskAuth(Subject(), Set(WhiskNamespace(ns2, ak2))))
    subs foreach (put(authStore, _))

    waitOnView(authStore, ak1, 1)
    waitOnView(authStore, ak2, 1)

    val s1 = Identity.get(authStore, ns1).futureValue
    s1.subject shouldBe subs(0).subject

    val s2 = Identity.get(authStore, ak2).futureValue
    s2.subject shouldBe subs(1).subject
  }

  it should "find subject by namespace with limits" in {
    implicit val tid: TransactionId = transid()
    val ak1 = AuthKey()
    val ak2 = AuthKey()
    val name1 = aname()
    val name2 = aname()
    val subs = Array(
      WhiskAuth(Subject(), Set(WhiskNamespace(name1, ak1))),
      WhiskAuth(Subject(), Set(WhiskNamespace(name2, ak2))))
    subs foreach (put(authStore, _))

    waitOnView(authStore, ak1, 1)
    waitOnView(authStore, ak2, 1)

    val limits = UserLimits(invocationsPerMinute = Some(7), firesPerMinute = Some(31))
    put(authStore, new LimitEntity(name1, limits))

    val i = Identity.get(authStore, name1).futureValue
    i.subject shouldBe subs(0).subject
    i.limits shouldBe limits
  }

  private class LimitEntity(name: EntityName, limits: UserLimits) extends WhiskAuth(Subject(), Set.empty) {
    override def docid = DocId(s"${name.name}/limits")

    //There is no api to write limits. So piggy back on WhiskAuth but replace auth json
    //with limits!
    override def toJson = UserLimits.serdes.write(limits).asJsObject
  }
}
