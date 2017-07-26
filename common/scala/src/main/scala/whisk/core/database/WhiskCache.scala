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

package whisk.core.database

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.CacheKey

abstract class WhiskCache[W, Winfo] {

    /**
     * This method posts a delete to the backing store, and either directly invalidates the cache entry
     * or informs any outstanding transaction that it must invalidate the cache on completion.
     */
    def cacheInvalidate[R](key: CacheKey, invalidator: => Future[R])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[R]

    /**
     * This method may initiate a read from the backing store, and potentially stores the result in the cache.
     */
    def cacheLookup[Wsub <: W](key: CacheKey, generator: => Future[Wsub])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging, mw: Manifest[Wsub]): Future[Wsub]

    /**
     * This method posts an update to the backing store, and potentially stores the result in the cache.
     */
    def cacheUpdate(doc: W, key: CacheKey, generator: => Future[Winfo])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[Winfo]

    /**
     * Removes an entry from the cache immediately and returns the entry if it exists.
     */
    protected[database] def removeId(key: CacheKey)(implicit ec: ExecutionContext): Option[Future[W]]
}
