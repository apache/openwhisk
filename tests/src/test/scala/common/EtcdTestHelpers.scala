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

package common

import io.etcd.jetcd.Response
import org.apache.openwhisk.core.etcd.EtcdClient

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Try}

trait EtcdTestHelpers {

  val etcd: EtcdClient

  type Entries = ListBuffer[String]

  class EntryCleaner(entriesToDeleteAfterTest: Entries) {
    def withCleaner[T <: Response](keys: List[String], sanitizer: String => Unit)(
      cmd: (EtcdClient, List[String]) => Unit): Unit = {
      keys.foreach(sanitizer)

      entriesToDeleteAfterTest ++= keys
      cmd(etcd, keys)
    }
  }

  def withEntryCleaner[T](sanitizer: String => Unit)(test: EntryCleaner => T): T = {
    val entriesToDeleteAfterTest = new Entries()

    try {
      test(new EntryCleaner(entriesToDeleteAfterTest))
    } catch {
      case t: Throwable =>
        // log the exception that occurred in the test and rethrow it
        println(s"Exception occurred during test execution: $t")
        t.printStackTrace()
        throw t
    } finally {
      val deletedAll = entriesToDeleteAfterTest.reverse.map { key =>
        key -> Try {
          sanitizer(key)
        }
      } forall {
        case (k, Failure(t)) =>
          println(s"ERROR: deleting entry failed for $k: $t")
          false
        case _ =>
          true
      }
      assert(deletedAll, "some entries were not deleted")
    }
  }

}

case class EtcdResult()
