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

package whisk.utils

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Scheduler

object ExecutionContextFactory {

  private type CancellableFuture[T] = (Cancellable, Future[T])
  private type MCF[T] = Either[Future[T], CancellableFuture[T]] // maybe cancellable future

  // akka.pattern.after has a memory drag issue: it opaquely
  // schedules an actor which consequently results in drag for the
  // timeout duration
  def expire[T](duration: FiniteDuration, using: Scheduler)(value: ⇒ Future[T])(implicit ec: ExecutionContext): MCF[T] =
    if (duration.isFinite() && duration.length < 1) {
      try Left(value)
      catch { case NonFatal(t) ⇒ Left(Future.failed(t)) }
    } else {
      val p = Promise[T]()
      val cancellable = using.scheduleOnce(duration) {
        p completeWith {
          try value
          catch { case NonFatal(t) ⇒ Future.failed(t) }
        }
      }
      Right((cancellable, p.future))
    }

  // Future.firstCompletedOf has a memory drag bug
  // https://stackoverflow.com/questions/36420697/about-future-firstcompletedof-and-garbage-collect-mechanism
  def firstCompletedOf[T](futures: TraversableOnce[Future[T]])(implicit executor: ExecutionContext): Future[T] = {
    val p = Promise[T]()
    val pref = new java.util.concurrent.atomic.AtomicReference(p)
    val completeFirst: Try[T] => Unit = { result: Try[T] =>
      val promise = pref.getAndSet(null)
      if (promise != null) {
        promise.tryComplete(result)
      }
    }
    futures foreach { _ onComplete completeFirst }
    p.future
  }

  def cancelAll[T](futures: TraversableOnce[MCF[T]]) = {
    futures foreach {
      _ match {
        case Right((cancellable, _)) => cancellable.cancel()
        case _                       => ()
      }
    }
  }

  def firstCompletedOf2[T](futures: TraversableOnce[MCF[T]])(implicit executor: ExecutionContext): Future[T] = {
    val p = Promise[T]()
    val pref = new java.util.concurrent.atomic.AtomicReference(p)
    val completeFirst: Try[T] => Unit = { result: Try[T] =>
      val promise = pref.getAndSet(null)
      if (promise != null) {
        promise.tryComplete(result)
      }
    }
    futures foreach {
      _ match {
        case Left(future)       => future onComplete completeFirst; cancelAll(futures)
        case Right((_, future)) => future onComplete completeFirst; cancelAll(futures)
      }
    }
    p.future
  }

  implicit class FutureExtensions[T](f: Future[T]) {
    def withTimeout(timeout: FiniteDuration, msg: => Throwable)(implicit system: ActorSystem): Future[T] = {
      implicit val ec = system.dispatcher
      firstCompletedOf2(Seq(Left(f), expire(timeout, system.scheduler)(Future.failed(msg))))
    }

    def withAlternativeAfterTimeout(timeout: FiniteDuration, alt: => Future[T])(
      implicit system: ActorSystem): Future[T] = {
      implicit val ec = system.dispatcher
      firstCompletedOf2(Seq(Left(f), expire(timeout, system.scheduler)(alt)))
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
