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

package org.apache.openwhisk.core.database.test.behavior

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.WhiskQueries.TOP
import org.apache.openwhisk.core.entity.{EntityPath, WhiskActivation}

trait ArtifactStoreActivationsQueryBehaviors extends ArtifactStoreBehaviorBase {

  it should "list activations between given times" in {
    implicit val tid: TransactionId = transid()
    val ns = newNS()
    val activations = (1000 until 1100 by 10).map(newActivation(ns.asString, "testact", _))
    activations foreach (put(activationStore, _))

    val entityPath = s"${ns.asString}/testact"
    waitOnView(activationStore, EntityPath(entityPath), activations.size, WhiskActivation.filtersView)

    val resultSince = query[WhiskActivation](
      activationStore,
      WhiskActivation.filtersView.name,
      List(entityPath, 1050),
      List(entityPath, TOP, TOP))

    resultSince.map(_.fields("value")) shouldBe activations.reverse
      .filter(_.start.toEpochMilli >= 1050)
      .map(_.summaryAsJson)

    val resultBetween = query[WhiskActivation](
      activationStore,
      WhiskActivation.filtersView.name,
      List(entityPath, 1060),
      List(entityPath, 1090, TOP))

    resultBetween.map(_.fields("value")) shouldBe activations.reverse
      .filter(a => a.start.toEpochMilli >= 1060 && a.start.toEpochMilli <= 1090)
      .map(_.summaryAsJson)
  }
}
