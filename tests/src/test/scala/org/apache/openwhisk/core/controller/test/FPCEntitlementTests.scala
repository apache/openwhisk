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

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.controller.RejectRequest
import org.apache.openwhisk.core.entitlement.{EntitlementProvider, FPCEntitlementProvider, Privilege, Resource}
import org.apache.openwhisk.core.entitlement.Privilege.{ACTIVATE, DELETE, PUT, READ, REJECT}
import org.apache.openwhisk.core.entity.{EntityName, EntityPath, FullyQualifiedEntityName}
import org.apache.openwhisk.core.loadBalancer.LoadBalancer
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FPCEntitlementProviderTests extends ControllerTestCommon with ScalaFutures with MockFactory {

  implicit val transactionId = TransactionId.testing

  it should "get throttle flag from loadBalancer" in {
    val someUser = WhiskAuthHelpers.newIdentity()
    val action = FullyQualifiedEntityName(EntityPath("testns"), EntityName("action"))
    val loadBalancer = mock[LoadBalancer]
    (loadBalancer
      .checkThrottle(_: EntityPath, _: String))
      .expects(someUser.namespace.name.toPath, action.fullPath.asString)
      .returning(true)
    val resources = Set(Resource(action.path, ACTIONS, Some(action.name.name)))

    val entitlementProvider: EntitlementProvider = new FPCEntitlementProvider(whiskConfig, loadBalancer, instance)
    entitlementProvider.checkThrottles(someUser, ACTIVATE, resources).failed.futureValue shouldBe a[RejectRequest]

    Seq[Privilege](READ, PUT, DELETE, REJECT).foreach(OP => {
      noException shouldBe thrownBy(entitlementProvider.checkThrottles(someUser, OP, resources).futureValue)
    })

    val action2 = FullyQualifiedEntityName(EntityPath("testns2"), EntityName("action2"))
    val resources2 = Set(Resource(action2.path, ACTIONS, Some(action2.name.name)))
    (loadBalancer
      .checkThrottle(_: EntityPath, _: String))
      .expects(someUser.namespace.name.toPath, action2.fullPath.asString)
      .returning(false)
    noException shouldBe thrownBy(entitlementProvider.checkThrottles(someUser, ACTIVATE, resources2).futureValue)
  }
}
