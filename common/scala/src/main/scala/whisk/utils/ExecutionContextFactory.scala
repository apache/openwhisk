/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.utils

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Try


import akka.actor.ActorSystem
import akka.pattern.{ after => expire }

object ExecutionContextFactory {

    implicit class FutureExtensions[T](f: Future[T]) {
        def withTimeout(timeout: FiniteDuration, msg: => Throwable)(implicit system: ActorSystem): Future[T] = {
            implicit val ec = system.dispatcher
            Future firstCompletedOf Seq(f, expire(timeout, system.scheduler)(Future.failed(msg)))
        }

        /**
         * method that converts a future into a successful future that holds the result of the initial future
         * inside of a Try
         */
        def toTryFuture(implicit context: ExecutionContext) : Future[Try[T]] = {
            val p = Promise[Try[T]]
            f.onComplete {
                t => p.trySuccess(t)
            }
            p.future
        }
    }

    /**
     * Extends a promise with an scheduled call back. The call back may be used to complete the promise. The result of the
     * call back is not interesting to this method.
     * The idiom to use is: promise after(duration, promise.tryFailure(TimeoutException)`.
     */
    implicit class PromiseExtensions[T](p: Promise[T]) {
        def after(timeout: FiniteDuration, next: => Unit)(implicit system: ActorSystem): Promise[T] = {
            implicit val ec = system.dispatcher
            expire(timeout, system.scheduler)(Future { next })
            p
        }
    }

    /**
     * Makes an execution context for Futures using Executors.newCachedThreadPool. From the javadoc:
     *
     * Creates a thread pool that creates new threads as needed, but will reuse previously constructed threads
     * when they are available. These pools will typically improve the performance of programs that execute many
     * short-lived asynchronous tasks. Calls to execute will reuse previously constructed threads if available.
     * If no existing thread is available, a new thread will be created and added to the pool. Threads that have
     * not been used for sixty seconds are terminated and removed from the cache. Thus, a pool that remains idle
     * for long enough will not consume any resources. Note that pools with similar properties but different details
     * (for example, timeout parameters) may be created using ThreadPoolExecutor constructors.
     */
    def makeCachedThreadPoolExecutionContext(): ExecutionContext = {
        ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    }
}
