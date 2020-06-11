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
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.entity.{EntityPath, WhiskActivation}
import spray.json.{JsNumber, JsObject}

import scala.util.Random

trait ActivationStoreQueryBehaviors extends ActivationStoreBehaviorBase {

  protected def checkQueryActivations(namespace: String,
                                      name: Option[String] = None,
                                      skip: Int = 0,
                                      limit: Int = 1000,
                                      includeDocs: Boolean = false,
                                      since: Option[Instant] = None,
                                      upto: Option[Instant] = None,
                                      context: UserContext,
                                      expected: IndexedSeq[WhiskActivation])(implicit transid: TransactionId): Unit = {
    val result =
      name
        .map { n =>
          activationStore
            .listActivationsMatchingName(
              EntityPath(namespace),
              EntityPath(n),
              skip = skip,
              limit = limit,
              includeDocs = includeDocs,
              since = since,
              upto = upto,
              context = context)
        }
        .getOrElse {
          activationStore
            .listActivationsInNamespace(
              EntityPath(namespace),
              skip = skip,
              limit = limit,
              includeDocs = includeDocs,
              since = since,
              upto = upto,
              context = context)
        }
        .map { r =>
          r.fold(left => left, right => right.map(wa => if (includeDocs) wa.toExtendedJson() else wa.summaryAsJson))
        }
        .futureValue

    result should contain theSameElementsAs expected.map(wa =>
      if (includeDocs) wa.toExtendedJson() else wa.summaryAsJson)
  }

  protected def checkCountActivations(namespace: String,
                                      name: Option[EntityPath] = None,
                                      skip: Int = 0,
                                      since: Option[Instant] = None,
                                      upto: Option[Instant] = None,
                                      context: UserContext,
                                      expected: Long)(implicit transid: TransactionId): Unit = {
    val result = activationStore
      .countActivationsInNamespace(
        EntityPath(namespace),
        name = name,
        skip = skip,
        since = since,
        upto = upto,
        context = context)
      .futureValue

    result shouldBe JsObject(WhiskActivation.collectionName -> JsNumber(expected))
  }

  behavior of s"${storeType}ActivationStore listActivationsInNamespace"

  it should "find all entities" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"
    val action2 = s"action2_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    val activations2 = (1000 until 1100 by 10).map(newActivation(namespace, action2, _))
    activations2 foreach (store(_, context))

    checkQueryActivations(namespace, context = context, expected = activations ++ activations2)
  }

  it should "support since and upto filters" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(
      namespace,
      since = Some(Instant.ofEpochMilli(1060)),
      context = context,
      expected = activations.takeRight(4))

    checkQueryActivations(
      namespace,
      upto = Some(Instant.ofEpochMilli(1060)),
      context = context,
      expected = activations.take(7))

    checkQueryActivations(
      namespace,
      since = Some(Instant.ofEpochMilli(1030)),
      upto = Some(Instant.ofEpochMilli(1060)),
      context = context,
      expected = activations.take(7).takeRight(4))
  }

  it should "support skipping results" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(namespace, skip = 5, context = context, expected = activations.take(5))
  }

  it should "support limiting results" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(namespace, limit = 5, context = context, expected = activations.takeRight(5))
  }

  it should "support including complete docs" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(namespace, includeDocs = true, context = context, expected = activations)
  }

  it should "throw exception for negative limits and skip" in {
    implicit val tid: TransactionId = transId()

    a[IllegalArgumentException] should be thrownBy activationStore
      .listActivationsInNamespace(EntityPath("test"), skip = -1, limit = 10, context = context)

    a[IllegalArgumentException] should be thrownBy activationStore
      .listActivationsInNamespace(EntityPath("test"), skip = 0, limit = -1, context = context)
  }

  behavior of s"${storeType}ActivationStore listActivationsMatchingName"

  it should "find all entities matching name" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"
    val action2 = s"action2_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    val activations2 = (1000 until 1100 by 10).map(newActivation(namespace, action2, _))
    activations2 foreach (store(_, context))

    checkQueryActivations(namespace, Some(action1), context = context, expected = activations)
  }

  it should "find all binding entities matching name" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val package1 = s"package1_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val binding = s"$namespace/$package1"

    val activations = (1000 until 1100 by 10).map(newBindingActivation(namespace, action1, binding, _))
    activations foreach (store(_, context))

    val activations2 = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations2 foreach (store(_, context))

    checkQueryActivations(namespace, Some(s"$package1/$action1"), context = context, expected = activations)
  }

  it should "support since and upto filters" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(
      namespace,
      Some(action1),
      since = Some(Instant.ofEpochMilli(1060)),
      context = context,
      expected = activations.takeRight(4))

    checkQueryActivations(
      namespace,
      Some(action1),
      upto = Some(Instant.ofEpochMilli(1060)),
      context = context,
      expected = activations.take(7))

    checkQueryActivations(
      namespace,
      Some(action1),
      since = Some(Instant.ofEpochMilli(1030)),
      upto = Some(Instant.ofEpochMilli(1060)),
      context = context,
      expected = activations.take(7).takeRight(4))
  }

  it should "support skipping results" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(namespace, Some(action1), skip = 5, context = context, expected = activations.take(5))
  }

  it should "support limiting results" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(namespace, Some(action1), limit = 5, context = context, expected = activations.takeRight(5))
  }

  it should "support including complete docs" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkQueryActivations(namespace, Some(action1), includeDocs = true, context = context, expected = activations)
  }

  it should "throw exception for negative limits and skip" in {
    implicit val tid: TransactionId = transId()

    a[IllegalArgumentException] should be thrownBy activationStore.listActivationsMatchingName(
      EntityPath("test"),
      name = EntityPath("testact"),
      skip = -1,
      limit = 10,
      context = context)

    a[IllegalArgumentException] should be thrownBy activationStore.listActivationsMatchingName(
      EntityPath("test"),
      name = EntityPath("testact"),
      skip = 0,
      limit = -1,
      context = context)
  }

  behavior of s"${storeType}ActivationStore countActivationsInNamespace"

  it should "should count all created activations" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkCountActivations(namespace, None, context = context, expected = 10)
  }

  it should "count with option name" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"
    val action2 = s"action2_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    val activations2 = (1000 until 1100 by 10).map(newActivation(namespace, action2, _))
    activations2 foreach (store(_, context))

    checkCountActivations(namespace, Some(EntityPath(action1)), context = context, expected = 10)

    checkCountActivations(namespace, Some(EntityPath(action2)), context = context, expected = 10)
  }

  it should "count with since and upto" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkCountActivations(namespace, None, since = Some(Instant.ofEpochMilli(1060L)), context = context, expected = 4)

    checkCountActivations(namespace, None, upto = Some(Instant.ofEpochMilli(1060L)), context = context, expected = 7)

    checkCountActivations(
      namespace,
      None,
      since = Some(Instant.ofEpochMilli(1030L)),
      upto = Some(Instant.ofEpochMilli(1060L)),
      context = context,
      expected = 4)
  }

  it should "count with skip" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action1 = s"action1_${Random.alphanumeric.take(4).mkString}"

    val activations = (1000 until 1100 by 10).map(newActivation(namespace, action1, _))
    activations foreach (store(_, context))

    checkCountActivations(namespace, None, skip = 4, context = context, expected = 10 - 4)
    checkCountActivations(namespace, None, skip = 1000, context = context, expected = 0)
  }

  it should "throw exception for negative skip" in {
    implicit val tid: TransactionId = transId()

    a[IllegalArgumentException] should be thrownBy activationStore.countActivationsInNamespace(
      namespace = EntityPath("test"),
      name = None,
      skip = -1,
      context = context)
  }
}
