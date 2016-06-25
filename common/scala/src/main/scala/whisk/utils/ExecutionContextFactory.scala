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

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.pattern.{ after => expire }

object ExecutionContextFactory {

    implicit class FutureExtensions[T](f: Future[T]) {
        def withTimeout(timeout: FiniteDuration, msg: => Throwable)(implicit system: ActorSystem, ec: ExecutionContext): Future[T] = {
            Future firstCompletedOf Seq(f, expire(timeout, system.scheduler)(Future.failed(msg)))
        }
    }

    /**
     * Extends a promise with an scheduled call back. The call back may be used to complete the promise. The result of the
     * call back is not interesting to this method.
     * The idiom to use is: promise after(duration, promise.tryFailure(TimeoutException)`.
     */
    implicit class PromiseExtensions[T](p: Promise[T]) {
        def after(timeout: FiniteDuration, next: => Unit)(implicit system: ActorSystem, ec: ExecutionContext): Promise[T] = {
            expire(timeout, system.scheduler)(Future { next })
            p
        }
    }

    /**
     * Makes an execution context for Futures using Executors.newSingleThreadExecutorl. From the javadoc:
     *
     * Creates an Executor that uses a single worker thread operating off an unbounded queue. (Note
     * however that if this single thread terminates due to a failure during execution prior to shutdown,
     * a new one will take its place if needed to execute subsequent tasks.) Tasks are guaranteed to execute
     * sequentially, and no more than one task will be active at any given time. Unlike the otherwise equivalent
     * newFixedThreadPool(1) the returned executor is guaranteed not to be reconfigurable to use additional threads.
     */
    def makeSingleThreadExecutionContext(): ExecutionContext = {
        ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
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

    /**
     * Makes an execution context for Futures from a general ThreadPoolExecutor.
     *
     * @param poolSize the number of threads to keep in the pool even if they are idle
     * @param maxPoolSize the maximum number of threads to allow in the pool
     * @param keepAliveDuration the maximum time that excess idle threads will wait for new tasks before terminating
     * @param workQueueSize the queue size for holding tasks before they are executed
     */
    def makeCustomThreadPoolExecutionContext(poolSize: Int = 16, maxPoolSize: Int = 32, keepAliveDuration: Duration = 10 seconds, workQueueSize: Int = 32767): ExecutionContext = {
        ExecutionContext.fromExecutor(new ThreadPoolExecutor(poolSize, maxPoolSize, keepAliveDuration.length, keepAliveDuration.unit, new ArrayBlockingQueue[Runnable](workQueueSize)))
    }

    /** Default execution context factory. */
    def makeExecutionContext() = makeCustomThreadPoolExecutionContext()
}
