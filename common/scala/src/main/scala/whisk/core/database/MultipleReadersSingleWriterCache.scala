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

package whisk.core.database

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import scala.util.Failure
import scala.util.Success

import spray.caching.Cache
import spray.caching.LruCache
import spray.caching.ValueMagnet.fromAny
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId

/**
 * A cache that allows multiple readers, bit only a single writer, at
 * a time. It will make a best effort attempt to coalesce reads, but
 * does not guarantee that all overlapping reads will be coalesced.
 *
 * The cache operates by bracketing all reads and writes. A read
 * imposes a lightweight read lock, by inserting an entry into the
 * cache with State.ReadInProgress. A write does the same, with
 * State.WriteInProgress.
 *
 * On read or write completion, the value transitions to
 * State.Cached.
 *
 * State.Initial is represented implicitly by absence from the cache.
 *
 * The handshake for cache entry state transition is:
 * 1. if entry is in an agreeable state, proceed
 *    where agreeable for reads is Initial, ReadInProgress, or Cached
 *                    and the read proceeds by ensuring the entry is State.ReadInProgress
 *      and agreeable for writes is Initial or Cached
 *                    and the write proceeds by ensuring the entry is State.WriteInProgress
 *      and agreeable for deletes is Cached
 *
 * 2. if entry is not in an agreeable state, then read- or write-around the cache;
 *    for deletions, we allow the delete to proceed, and mark the entry as InvalidateWhenDone
 *    the owning reader or writer is then responsible for ensuring that the entry is invalid
 *    when that read or write completes
 *
 * 3. to swap in the new state to an existing entry, we use an AtomicReference.compareAndSet
 *
 * 4. only if the db operation completes with success, atomically set the state to Cached.
 *
 * 5. lastly, for cache invalidations that race with, we mark the entry as
 *
 */
trait MultipleReadersSingleWriterCache[W, Winfo] {
    /** Subclasses: Toggle this to enable/disable caching for your entity type. */
    protected def cacheEnabled = true

    /** Subclasses: tell me what key to use for updates */
    protected def cacheKeyForUpdate(w: W): Any

    /** Each entry has a state, as explained in the class comment above */
    protected object State extends Enumeration {
        type State = Value
        val ReadInProgress, WriteInProgress, InvalidateInProgress, InvalidateWhenDone, Cached = Value
    }
    import State._
    implicit def state2Atomic(state: State): AtomicReference[State] = {
        new AtomicReference(state);
    }

    /** Failure modes, which will only occur if there is a bug in this implementation */
    case class ConcurrentOperationUnderRead(actualState: State) extends Exception(s"Cache bug: a read started, but completion raced with a concurrent operation: ${actualState}")
    case class ConcurrentOperationUnderUpdate(actualState: State) extends Exception("Cache bug: an update started, but completion raced with a concurrent operation: ${actualState}")
    case class SquashedInvalidation(actualState: State) extends Exception("Cache invalidation squashed due ${actualState}")

    /**
     * The entries in the cache will be a triple of (transid, State, Future[W])
     *
     * We need the transid in order to detect whether we have won the race to add an entry to the cache
     *
     */
    private case class Entry(transid: TransactionId, state: AtomicReference[State], value: Future[W]) {
        def invalidate = state.set(InvalidateInProgress)

        def writeDone()(implicit logger: Logging): Boolean = {
            logger.debug(this, "Write finished")
            trySet(WriteInProgress, Cached)
        }

        def readDone()(implicit logger: Logging): Boolean = {
            logger.debug(this, "Read finished")
            trySet(ReadInProgress, Cached)
        }

        def trySet(expectedState: State, desiredState: State): Boolean = {
            state.compareAndSet(expectedState, desiredState)
        }
    }

    protected def cacheInvalidate[R](key: Any, afterInvalidate: => Future[R])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[R] = {
        if (cacheEnabled) {
            logger.info(this, s"invalidating $key")

            cache.remove(key) map { entryToBeRemovedFuture =>
                entryToBeRemovedFuture flatMap { entryToBeRemoved =>
                    entryToBeRemoved.state.get match {
                        case ReadInProgress | WriteInProgress => {
                            //Future.failed[Winfo](new SquashedInvalidation(state))
                            entryToBeRemoved.state.set(InvalidateWhenDone)
                        }
                        case Cached => {
                            entryToBeRemoved.state.set(InvalidateInProgress)
                        }
                    }
                    afterInvalidate
                }

            } getOrElse {
                // not found in cache
                afterInvalidate
            }

        } else {
            // not caching
            afterInvalidate
        }
    }

    protected def cacheLookup[Wsuper >: W](
        key: Any,
        future: => Future[W],
        fromCache: Boolean = cacheEnabled)(
            implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[W] = {

        if (fromCache) {
            val promise = Promise[W]
            val desiredEntry = Entry(transid, ReadInProgress, promise.future)

            //
            // try inserting our desired entry...
            //
            cache(key)(desiredEntry) flatMap { actualEntry =>
                // ... and see what we get back

                actualEntry.state.get match {
                    case Cached => {
                        logger.debug(this, s"Cached read $key")
                        makeNoteOfCacheHit(key)
                        actualEntry.value
                    }

                    case ReadInProgress => {
                        if (actualEntry.transid == transid) {
                            logger.debug(this, "Read initiated");
                            makeNoteOfCacheMiss(key)
                            listenForReadDone(key, actualEntry, future, promise)
                            actualEntry.value

                        } else {
                            logger.debug(this, "Coalesced read")
                            makeNoteOfCacheHit(key)
                            actualEntry.value
                        }
                    }

                    case WriteInProgress | InvalidateInProgress => {
                        logger.debug(this, "Reading around a write in progress")
                        makeNoteOfCacheMiss(key)
                        future
                    }
                }
            }

        } else {
            // not caching
            future
        }
    }

    /**
     * This method posts an update to the backing store, and potentially stores the result in the cache.
     *
     */
    protected def cacheUpdate(doc: W, key: Any, future: => Future[Winfo])(
        implicit transid: TransactionId, logger: Logging, ec: ExecutionContext): Future[Winfo] = {

        if (cacheEnabled) {
            logger.info(this, s"invalidating $key") // make the tests happy, as cacheUpdate now has invalidate built in
            logger.info(this, s"caching $key")

            val desiredEntry = Entry(transid, WriteInProgress, Future { doc })

            //
            // try inserting our desired entry...
            //
            cache(key)(desiredEntry) flatMap { actualEntry =>
                // ... and see what we get back

                if (transid == actualEntry.transid) {
                    //
                    // then we won the race, and now own the entry
                    //
                    logger.info(this, s"Write initiated $key")
                    listenForWriteDone(key, actualEntry, future)

                } else {
                    //
                    // then either we lost the race, or there is an operation in progress on this key
                    //
                    // note that we don't care what that other operation is doing; unlike for lookups,
                    // we don't need to do anything special (e.g. coalescing, as in the case of lookups)
                    //
                    logger.info(this, s"Write under ${actualEntry.state.get} $key")
                    future
                }
            }

        } else {
            // not caching
            future
        }
    }

    def cacheSize: Int = cache.size

    /**
     * Log a cache hit
     *
     */
    private def makeNoteOfCacheHit(key: Any)(implicit transid: TransactionId, logger: Logging) {
        transid.mark(this, LoggingMarkers.DATABASE_CACHE_HIT, s"[GET] serving from cache: $key")(logger)
    }

    /**
     * Log a cache miss
     *
     */
    private def makeNoteOfCacheMiss(key: Any)(implicit transid: TransactionId, logger: Logging) {
        transid.mark(this, LoggingMarkers.DATABASE_CACHE_MISS, s"[GET] serving from datastore: $key")(logger)
    }

    /**
     * We have initiated a read (in cacheLookup), now handle its completion
     *
     */
    private def listenForReadDone(key: Any, entry: Entry, future: => Future[W], promise: Promise[W])(
        implicit logger: Logging, ec: ExecutionContext): Unit = {

        future onComplete {
            case Success(value) => {
                //
                // if the datastore read was successful, then transition to the Cached state
                //
                logger.debug(this, "Read backend part done, now marking cache entry as done")
                if (entry.readDone()) {
                    promise success value

                } else if (entry.state.get == InvalidateWhenDone) {
                    invalidateEntry(key, entry)

                } else {
                    // if this ever happens, this cache impl is buggy
                    readOops(key, entry, promise, ConcurrentOperationUnderRead(entry.state.get))
                }
            }
            case Failure(t) => {
                //
                // oops, the datastore read failed. invalidate the cache entry
                //
                // note that this might be a perfectly legitimate failure,
                // e.g. a lookup for a non-existant key; we need to pass the particular t through
                //
                readOops(key, entry, promise, t)
            }
        }
    }

    /**
     * We have initiated a write, now handle its completion
     *
     */
    private def listenForWriteDone(key: Any, entry: Entry, future: => Future[Winfo])(
        implicit logger: Logging, ec: ExecutionContext): Future[Winfo] = {

        future flatMap { docinfo =>
            //
            // if the datastore write was successful, then transition to the Cached state
            //
            logger.debug(this, "Write backend part done, now marking cache entry as done")

            if (!entry.writeDone()) {
                if (entry.state.get != InvalidateWhenDone) {
                    // if this ever happens, this cache impl is buggy
                    logger.error(this, ConcurrentOperationUnderUpdate.toString())
                } else {
                    logger.info(this, s"Write done, but invalidating cache entry $key ${entry.transid}")
                }
                invalidateEntry(key, entry)

            } else {
                logger.info(this, s"Write all done $key ${entry.transid} ${entry.state.get}")
            }

            Future.successful(docinfo)

        } recoverWith {
            case t =>
                //
                // oops, the datastore write failed. invalidate the cache entry
                //
                invalidateEntry(key, entry)
                Future.failed(t)
        }
    }

    /**
     * Write completion resulted in a failure
     *
     */
    private def readOops(key: Any, entry: Entry, promise: Promise[W], t: Throwable): Unit = {
        invalidateEntry(key, entry)
        promise failure t
    }

    /**
     * Write completion resulted in a failure
     *
     */
    private def invalidateEntry(key: Any, entry: Entry): Unit = {
        entry.invalidate
        cache remove key
    }

    /**
     * This is the backing store
     */
    private val cache: Cache[Entry] = LruCache(timeToLive = 5.minutes)
}
