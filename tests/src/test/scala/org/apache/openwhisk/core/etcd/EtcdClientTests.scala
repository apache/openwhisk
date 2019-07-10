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
package org.apache.openwhisk.core.etcd

import java.nio.charset.StandardCharsets
import java.util
import java.util.Collections
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import common.{EtcdTestHelpers, StreamLogging, WskActorSystem}
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.options.PutOption
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.jetcd.watch.WatchResponse
import io.grpc.StatusRuntimeException
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.etcd.EtcdType._
import org.junit.runner.RunWith
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import pureconfig.loadConfigOrThrow

import scala.collection.JavaConverters
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class EtcdClientTests
    extends FlatSpec
    with ScalaFutures
    with Matchers
    with WskActorSystem
    with StreamLogging
    with EtcdTestHelpers {

  implicit val timeout = Timeout(2.seconds)

  val etcd = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd).hosts)

  val prefix = "dummyOpenWhisk"
  val typeA = "dummyA"
  val typeB = "dummyB"
  val typeC = "dummyC"
  val dummyValue = "dummyValue"
  val id1 = 1
  val id2 = 2

  val TOP = "\ufff0"

  val keyA1 = dummyKey(prefix, typeA, id1) //  dummyOpenWhisk/dummyA/1
  val keyB1 = dummyKey(prefix, typeB, id1) //  dummyOpenWhisk/dummyB/1
  val keyC1 = dummyKey(prefix, typeC, id1) //  dummyOpenWhisk/dummyC/1

  val keyA2 = dummyKey(prefix, typeA, id2) //  dummyOpenWhisk/dummyA/1
  val keyB2 = dummyKey(prefix, typeB, id2) //  dummyOpenWhisk/dummyB/2
  val keyC2 = dummyKey(prefix, typeC, id2) //  dummyOpenWhisk/dummyC/3

  private def getFirstValue(res: GetResponse): String = res.getKvs.get(0).getValue

  private def dummyKey(prefix: String, keyType: String, id: Int) = s"$prefix/$keyType/$id"

  private def zipWithKey(list: java.util.List[KeyValue]): Seq[String] =
    JavaConverters
      .asScalaIteratorConverter(list.iterator())
      .asScala
      .map(kv => kv.getKey.toString(StandardCharsets.UTF_8))
      .toSeq

  private def sanitizer(key: String): Unit = {
    noException should be thrownBy etcd.del(key).futureValue(timeout)
  }

  private def responseToLease(res: LeaseGrantResponse) = Lease(res.getID, res.getTTL)

  behavior of "EtcdClient"

  "Etcd KV client" should "be able to put, get and delete" in {
    val key = "openwhisk"
    val value = "openwhisk"

    // Create an entry
    noException should be thrownBy etcd.put(key, value).futureValue(timeout)

    // Gets the entry
    getFirstValue(etcd.get(key).futureValue) should equal(value)

    // Deletes the entry
    noException should be thrownBy etcd.del(key).futureValue

    // Asserts that the entry is gone
    etcd.get(key).futureValue.getKvs.isEmpty should equal(true)

  }

  "Etcd KV client" should "be able to get by range query" in withEntryCleaner(sanitizer) { entryHelper =>
    entryHelper.withCleaner(List(keyA1, keyB1, keyC1, keyA2, keyB2, keyC2), sanitizer) { (etcd, keys) =>
      keys.foreach { key =>
        noException should be thrownBy etcd.put(key, dummyValue).futureValue(timeout)
      }
    }

    val startKey = s"$prefix/$typeA" // dummyOpenWhisk/dummyA
    val endKey = startKey + TOP

    val kvs = etcd.getRange(startKey, endKey).futureValue.getKvs

    zipWithKey(kvs) shouldBe Seq(keyA1, keyA2)
  }

  "Etcd KV client" should "be able to get by prefix range query" in withEntryCleaner(sanitizer) { entryHelper =>
    entryHelper.withCleaner(List(keyA1, keyB1, keyC1, keyA2, keyB2, keyC2), sanitizer) { (etcd, keys) =>
      keys.foreach { key =>
        noException should be thrownBy etcd.put(key, dummyValue).futureValue(timeout)
      }
    }

    val kvs = etcd.getPrefix(prefix).futureValue.getKvs

    zipWithKey(kvs) shouldBe Seq(keyA1, keyA2, keyB1, keyB2, keyC1, keyC2)

  }

  "Etcd Lease client" should "be able to grant, and revoke" in {
    val ttl = 2

    // Create lease
    val lease1 = responseToLease(etcd.grant(ttl).futureValue)

    // Revoke lease successfully
    noException should be thrownBy etcd.revoke(lease1).futureValue

    // Create lease
    val lease2 = responseToLease(etcd.grant(ttl).futureValue)

    // Create timeout
    Thread.sleep(5000)

    // The lease is already gone
    etcd.revoke(lease2).failed.futureValue.getCause shouldBe a[StatusRuntimeException]
  }

  "Etcd Lease client" should "be able to keepAliveOnce" in {
    val ttl = 3

    // Create lease
    val lease = responseToLease(etcd.grant(ttl).futureValue)

    // Wait 2 seconds
    Thread.sleep(2000)

    // Extend timeout
    noException should be thrownBy etcd.keepAliveOnce(lease)

    // Wait 2 seconds
    Thread.sleep(2000)

    //The lease was not deleted and successfully revoked.
    noException should be thrownBy etcd.revoke(lease).futureValue

  }

  "Etcd KV client" should "be able to put with timeout" in {

    val key = "openwhisk"
    val value = "openwhisk"

    val leaseTimeout = 3
    val lease = responseToLease(etcd.grant(leaseTimeout).futureValue)

    val option = PutOption.newBuilder().withLeaseId(lease.id).build()

    // Save kv
    noException should be thrownBy etcd.put(key, value, option).futureValue
    // Verify that the key/value is saved
    getFirstValue(etcd.get(key).futureValue) shouldBe value

    // Create timeout
    Thread.sleep(5000)

    // The key/value is gone
    etcd.get(key).futureValue.getCount shouldBe 0
  }

  "Etcd KV client" should "throw StatusRuntimeException when lease doesn't exist" in {

    val key = "openwhisk"
    val value = "openwhisk"

    val leaseId = 1234567890l
    val option = PutOption.newBuilder().withLeaseId(leaseId).build()

    // Save kv
    etcd.put(key, value, option).failed.futureValue shouldBe a[StatusRuntimeException]
  }

  "Etcd Watch client" should "watch on put" in {

    val ref = new AtomicReference[WatchResponse]()

    val key = "watchtest"
    val value = "openwhisk"

    def onNext(res: WatchResponse) {
      ref.set(res)
    }

    etcd.watch(key)(onNext)

    etcd.put(key, value)
    Thread.sleep(1000)

    ref.get() should not be null
    ref.get().getEvents.size() shouldEqual 1
    ref.get().getEvents.get(0).getEventType shouldEqual EventType.PUT
    ref.get().getEvents.get(0).getKeyValue.getKey.toString(StandardCharsets.UTF_8) shouldBe key
    ref.get().getEvents.get(0).getKeyValue.getValue.toString(StandardCharsets.UTF_8) shouldBe value

  }

  "Etcd Watch client" should "multi watch on put" in {

    // In Scala all synchronization collections are going to be deprecated. So I used java collection.
    val refs = Collections.synchronizedList(new util.ArrayList[WatchResponse](2))

    val key = "watchtest"
    val value = "openwhisk"

    def onNext(res: WatchResponse) {
      refs.add(res)
    }

    etcd.watch(key)(onNext)
    etcd.watch(key)(onNext)

    etcd.put(key, value)
    Thread.sleep(1000)

    refs.size shouldEqual 2
    refs.get(0).getEvents.size() shouldEqual 1
    refs.get(0).getEvents.get(0).getEventType shouldEqual EventType.PUT
    refs.get(0).getEvents.get(0).getKeyValue.getKey.toString(StandardCharsets.UTF_8) shouldBe key
    refs.get(0).getEvents.get(0).getKeyValue.getValue.toString(StandardCharsets.UTF_8) shouldBe value

  }

  "Etcd Watch client" should "watch on delete" in {

    val ref = new AtomicReference[WatchResponse]()

    val key = "watchtest"
    val value = "openwhisk"

    def onNext(res: WatchResponse) {
      ref.set(res)
    }

    etcd.put(key, value)

    etcd.watch(key)(onNext)

    etcd.del(key)
    Thread.sleep(1000)

    ref.get()
    ref.get() should not be null
    ref.get().getEvents.size() shouldEqual 1
    ref.get().getEvents.get(0).getEventType shouldEqual EventType.DELETE
    ref.get().getEvents.get(0).getKeyValue.getKey.toString(StandardCharsets.UTF_8) shouldBe key
    ref.get().getEvents.get(0).getKeyValue.getValue.toString(StandardCharsets.UTF_8) shouldBe ""

  }

  "Etcd Watch client" should "listen on close" in {

    val ref = new AtomicBoolean

    val key = "watchtest"

    def onCompleted() {
      ref.set(true)
    }

    val watcher = etcd.watch(key)(completed = () => onCompleted())

    Thread.sleep(100)

    watcher.close()

    Thread.sleep(1000)

    ref.get() shouldBe true
  }

}
