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

package whisk.core.database.cosmosdb

import rx.lang.scala.JavaConverters._
import rx.Observable

import scala.concurrent.{Future, Promise}

trait RxObservableImplicits {

  implicit class RxScalaObservable[T](observable: Observable[T]) {

    /**
     * Returns the head of the [[Observable]] in a [[scala.concurrent.Future]].
     *
     * @return the head result of the [[Observable]].
     */
    def head(): Future[T] = {
      val promise = Promise[T]()
      observable.asScala.single.subscribe(x => promise.success(x), e => promise.failure(e))
      promise.future
    }

    /**
     * Collects the [[Observable]] results and converts to a [[scala.concurrent.Future]].
     *
     * Automatically subscribes to the `Observable` and uses the [[Observable#toList]] method to aggregate the results.
     *
     * @note If the Observable is large then this will consume lots of memory!
     *       If the underlying Observable is infinite this Observable will never complete.
     * @return a future representation of the whole Observable
     */
    def toFuture(): Future[Seq[T]] = {
      val promise = Promise[Seq[T]]()
      observable.asScala.toList.subscribe(x => promise.success(x), e => promise.failure(e))
      promise.future
    }
  }
}
