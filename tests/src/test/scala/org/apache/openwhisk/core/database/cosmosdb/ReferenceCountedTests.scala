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

package org.apache.openwhisk.core.database.cosmosdb

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class ReferenceCountedTests extends FlatSpec with Matchers {

  class CloseProbe extends AutoCloseable {
    var closed: Boolean = _
    var closedCount: Int = _
    override def close(): Unit = {
      closed = true
      closedCount += 1
    }
  }

  behavior of "ReferenceCounted"

  it should "close only once" in {
    val probe = new CloseProbe
    val refCounted = ReferenceCounted(probe)

    val ref1 = refCounted.reference()

    ref1.get should be theSameInstanceAs probe
    ref1.close()
    ref1.close()

    probe.closed shouldBe true
    probe.closedCount shouldBe 1
    refCounted.isClosed shouldBe true
  }

  it should "not close with one reference active" in {
    val probe = new CloseProbe
    val refCounted = ReferenceCounted(probe)

    val ref1 = refCounted.reference()
    val ref2 = refCounted.reference()

    ref1.close()
    ref1.close()

    probe.closed shouldBe false
  }

  it should "be closed when all reference close" in {
    val probe = new CloseProbe
    val refCounted = ReferenceCounted(probe)

    val ref1 = refCounted.reference()
    val ref2 = refCounted.reference()

    ref1.close()
    ref2.close()

    probe.closed shouldBe true
    probe.closedCount shouldBe 1
  }

  it should "throw exception if closed" in {
    val probe = new CloseProbe
    val refCounted = ReferenceCounted(probe)

    val ref1 = refCounted.reference()
    ref1.close()

    intercept[IllegalArgumentException] {
      refCounted.reference()
    }
  }
}
