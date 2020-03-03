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

package org.apache.openwhisk.core.database.elasticsearch

import java.time.Instant

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.database.test.behavior.ActivationStoreBehavior
import org.apache.openwhisk.core.entity.{EntityPath, WhiskActivation}
import org.apache.openwhisk.utils.retry

@RunWith(classOf[JUnitRunner])
class ElasticSearchActivationStoreTests
    extends FlatSpec
    with ElasticSearchActivationStoreBehaviorBase
    with ActivationStoreBehavior {

  override def checkGetActivation(activation: WhiskActivation)(implicit transid: TransactionId): Unit = {
    retry(super.checkGetActivation(activation), 10)
  }

  override def checkDeleteActivation(activation: WhiskActivation)(implicit transid: TransactionId): Unit = {
    retry(super.checkDeleteActivation(activation), 10)
  }

  override def checkQueryActivations(namespace: String,
                                     name: Option[String] = None,
                                     skip: Int = 0,
                                     limit: Int = 1000,
                                     includeDocs: Boolean = false,
                                     since: Option[Instant] = None,
                                     upto: Option[Instant] = None,
                                     context: UserContext,
                                     expected: IndexedSeq[WhiskActivation])(implicit transid: TransactionId): Unit = {
    retry(super.checkQueryActivations(namespace, name, skip, limit, includeDocs, since, upto, context, expected), 10)
  }

  override def checkCountActivations(namespace: String,
                                     name: Option[EntityPath] = None,
                                     skip: Int = 0,
                                     since: Option[Instant] = None,
                                     upto: Option[Instant] = None,
                                     context: UserContext,
                                     expected: Long)(implicit transid: TransactionId): Unit = {
    retry(super.checkCountActivations(namespace, name, skip, since, upto, context, expected), 10)
  }
}
