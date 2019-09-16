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

import java.time.Instant

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.WhiskQueries.TOP
import org.apache.openwhisk.core.entity._

trait ArtifactStoreWhisksQueryBehaviors extends ArtifactStoreBehaviorBase {

  behavior of s"${storeType}ArtifactStore query public packages"

  it should "only list published entities" in {
    implicit val tid: TransactionId = transid()
    val ns = newNS()
    val n1 = aname()
    val pkgs = Array(
      WhiskPackage(ns, aname()),
      WhiskPackage(ns, aname(), publish = true),
      WhiskPackage(ns, aname(), publish = true, binding = Some(Binding(aname(), aname()))),
      WhiskPackage(ns, aname(), binding = Some(Binding(aname(), aname()))))

    pkgs foreach (put(entityStore, _))

    waitOnView(entityStore, WhiskPackage, ns, pkgs.length)

    val result =
      query[WhiskEntity](entityStore, WhiskPackage.view.name, List(ns.asString, 0), List(ns.asString, TOP, TOP))
    result.size shouldBe pkgs.length

    val resultPublic =
      query[WhiskEntity](
        entityStore,
        WhiskPackage.publicPackagesView.name,
        List(ns.asString, 0),
        List(ns.asString, TOP, TOP))

    resultPublic.size shouldBe 1
    resultPublic.head.fields("value") shouldBe pkgs(1).summaryAsJson
  }

  it should "list packages between given times" in {
    implicit val tid: TransactionId = transid()

    val ns = newNS()
    val pkgs = (1000 until 1100 by 10).map(new TestWhiskPackage(ns, aname(), _))

    pkgs foreach (put(entityStore, _))

    waitOnView(entityStore, WhiskPackage, ns, pkgs.length)

    val resultSince =
      query[WhiskEntity](entityStore, WhiskPackage.view.name, List(ns.asString, 1050), List(ns.asString, TOP, TOP))

    resultSince.map(_.fields("value")) shouldBe pkgs.reverse.filter(_.updated.toEpochMilli >= 1050).map(_.summaryAsJson)

    val resultBetween =
      query[WhiskEntity](entityStore, WhiskPackage.view.name, List(ns.asString, 1050), List(ns.asString, 1090, TOP))
    resultBetween.map(_.fields("value")) shouldBe pkgs.reverse
      .filter(p => p.updated.toEpochMilli >= 1050 && p.updated.toEpochMilli <= 1090)
      .map(_.summaryAsJson)
  }

  private class TestWhiskPackage(override val namespace: EntityPath, override val name: EntityName, updatedTest: Long)
      extends WhiskPackage(namespace, name) {
    //Not possible to easily control the updated so need to use this workaround
    override val updated = Instant.ofEpochMilli(updatedTest)
  }
}
