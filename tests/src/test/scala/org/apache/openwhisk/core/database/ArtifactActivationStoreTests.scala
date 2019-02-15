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

package org.apache.openwhisk.core.database

import java.time.Instant

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.test.behavior.ActivationStoreBehavior
import org.apache.openwhisk.core.entity.{EntityPath, WhiskActivation}
import org.apache.openwhisk.utils.retry
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ArtifactActivationStoreTests
    extends FlatSpec
    with ArtifactActivationStoreBehaviorBase
    with ActivationStoreBehavior {
  override def checkQueryActivations(namespace: String,
                                     name: Option[String] = None,
                                     skip: Int = 0,
                                     limit: Int = 1000,
                                     includeDocs: Boolean = false,
                                     since: Option[Instant] = None,
                                     upto: Option[Instant] = None,
                                     context: UserContext,
                                     expected: IndexedSeq[WhiskActivation])(implicit transid: TransactionId): Unit = {
    // This is for compatible with CouchDB as it use option `StaleParameter.UpdateAfter`
    retry(super.checkQueryActivations(namespace, name, skip, limit, includeDocs, since, upto, context, expected), 100)
  }

  override def checkCountActivations(namespace: String,
                                     name: Option[EntityPath] = None,
                                     skip: Int = 0,
                                     since: Option[Instant] = None,
                                     upto: Option[Instant] = None,
                                     context: UserContext,
                                     expected: Long)(implicit transid: TransactionId): Unit = {
    retry(super.checkCountActivations(namespace, name, skip, since, upto, context, expected), 100)
  }
}
