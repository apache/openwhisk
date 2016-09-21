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
import scala.concurrent.ExecutionContext.Implicits.global
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
 *
 * 2. if entry is not in an agreeable state, fail fast with Future.failed(new ConcurrentUpdateException)
 *
 * 3. to swap in the new state to an existing entry, we use an AtomicReference.compareAndSet
 *
 * 4. after adding a new entry, we double check that the state of the actually-added entry
 *    is agreeable with the desired action (as per item 1)
 *
 */
trait MultipleReadersSingleWriterCache[W, Winfo] {
    /** Subclasses: Toggle this to enable/disable caching for your entity type. */
    protected def cacheEnabled = true

    /** Subclasses: tell me what key to use for updates */
    protected def cacheKeyForUpdate(w: W): Any

    /**
     * Each entry has a state
     *
     */
    protected object State extends Enumeration {
        type State = Value
        val ReadInProgress, WriteInProgress, InvalidateInProgress, Cached = Value
    }
    import State._;
    implicit def state2Atomic(state: State): AtomicReference[State] = {
        new AtomicReference(state);
    }

    /**
     * Failure modes
     *
     */
    private class ConcurrentUpdateException extends Exception {}

    /**
     * The entries in the cache will be a pair of State and the Future value
     *
     */
    private case class Entry(transid: TransactionId, state: AtomicReference[State], value: Future[W]) {
        def invalidate {
            state.set(InvalidateInProgress)
        }

        def writeDone()(implicit logger: Logging): Boolean = {
            logger.debug(this, "Write finished")
            trySet(WriteInProgress, Cached)
        }

        def readDone(value: W, promise: Promise[W])(implicit logger: Logging): Unit = {
            logger.info(this, "Read finished")

            if (trySet(ReadInProgress, Cached)) {
                promise success value
            } else {
                promise failure new ConcurrentUpdateException
            }
        }

        def trySet(expectedState: State, desiredState: State): Boolean = {
            state.compareAndSet(expectedState, desiredState)
        }
    }

    protected def cacheInvalidate(key: Any)(
        implicit transid: TransactionId, logger: Logging): Unit = {
        if (cacheEnabled) {
            logger.info(this, s"invalidating $key")
            cache remove key
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
            cache(key)(desiredEntry) flatMap {
                // ... and see what we get back
                case actualEntry =>
                    // logger.debug(this, s"Cache Lookup Status: the entry is currently in state ${actualEntry.state.get} ${getSize()}")

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

    protected def cacheUpdate(doc: W, key: Any, future: => Future[Winfo])(
        implicit transid: TransactionId, logger: Logging, ec: ExecutionContext): Promise[Winfo] = {

        val valPromise = Promise[W]
        val promise = Promise[Winfo]

        if (cacheEnabled) {
            logger.info(this, s"caching $key")

            val desiredEntry = Entry(transid, WriteInProgress, valPromise.future)

            //
            // try inserting our desired entry...
            //
            cache(key)(desiredEntry) map {
                // ... and see what we get back
                case actualEntry =>
                    if (transid == actualEntry.transid) {
                        //
                        // then we won the race, and now own the entry
                        //
                        logger.debug(this, s"Write initiated $key")
                        listenForWriteDone(doc, key, actualEntry, future, valPromise, promise)

                    } else {
                        //
                        // then we either lost the race, or there is an operation in progress on this key
                        //
                        logger.debug(this, s"Write under ${actualEntry.state.get} $key")
                        promise completeWith future
                    }
            }

        } else {
            // not caching
            promise completeWith future
        }

        promise
    }

    def getSize() = cache.size

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
        implicit logger: Logging): Unit = {

        future onComplete {
            case Success(value) => entry.readDone(value, promise)
            case Failure(t)     => readOops(key, entry, promise, t)
        }
    }

    /**
     * We have initiated a write, now handle its completion
     *
     */
    private def listenForWriteDone(doc: W, key: Any, entry: Entry, future: => Future[Winfo], valPromise: Promise[W], promise: Promise[Winfo])(
        implicit logger: Logging): Unit = {

        future onComplete {
            case Success(docinfo) => {
                //
                // if the datastore write was successful, then transition to the Cached state
                //
                logger.debug(this, "Write backend part done, now marking cache entry as done")

                if (!entry.writeDone()) {
                    logger.error(this, "Concurrent update detected")
                    writeOops(key, entry, valPromise, promise, new ConcurrentUpdateException)

                } else {
                    logger.debug(this, s"Write all done $key ${entry.transid} ${entry.state.get} ${getSize()}")
                    promise success docinfo
                    valPromise success doc
                }
            }
            case Failure(t) => {
                //
                // oops, the datastore write failed. invalidate the cache entry
                //
                writeOops(key, entry, valPromise, promise, t)
            }
        }
    }

    /**
     * Write completion resulted in a failure
     *
     */
    private def readOops(key: Any, entry: Entry, promise: Promise[W], t: Throwable) {
        entry.invalidate
        cache remove key
        promise failure t
    }

    /**
     * Write completion resulted in a failure
     *
     */
    private def writeOops(key: Any, entry: Entry, valPromise: Promise[W], promise: Promise[Winfo], t: Throwable) {
        entry.invalidate
        cache remove key
        promise failure t
        valPromise failure t
    }

    /**
     * This is the backing store
     */
    private val cache: Cache[Entry] = LruCache.apply(timeToLive = 5.minutes)
}
