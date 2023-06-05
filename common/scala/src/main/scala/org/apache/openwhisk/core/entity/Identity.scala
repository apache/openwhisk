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

package org.apache.openwhisk.core.entity

import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.database.{
  MultipleReadersSingleWriterCache,
  NoDocumentException,
  StaleParameter,
  WriteTime
}
import org.apache.openwhisk.core.entitlement.Privilege
import org.apache.openwhisk.core.entity.types.AuthStore
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

case class UserLimits(invocationsPerMinute: Option[Int] = None,
                      concurrentInvocations: Option[Int] = None,
                      firesPerMinute: Option[Int] = None,
                      allowedKinds: Option[Set[String]] = None,
                      storeActivations: Option[Boolean] = None,
                      minActionMemory: Option[MemoryLimit] = None,
                      maxActionMemory: Option[MemoryLimit] = None,
                      minActionLogs: Option[LogLimit] = None,
                      maxActionLogs: Option[LogLimit] = None,
                      minActionTimeout: Option[TimeLimit] = None,
                      maxActionTimeout: Option[TimeLimit] = None,
                      minActionConcurrency: Option[IntraConcurrencyLimit] = None,
                      maxActionConcurrency: Option[IntraConcurrencyLimit] = None,
                      maxParameterSize: Option[ByteSize] = None,
                      maxPayloadSize: Option[ByteSize] = None,
                      truncationSize: Option[ByteSize] = None,
                      warmedContainerKeepingCount: Option[Int] = None,
                      warmedContainerKeepingTimeout: Option[String] = None,
                      maxActionInstances: Option[Int] = None) {

  def allowedMaxParameterSize: ByteSize = {
    val namespaceLimit = maxParameterSize getOrElse (Parameters.MAX_SIZE_DEFAULT)
    if (namespaceLimit > Parameters.MAX_SIZE) {
      Parameters.MAX_SIZE
    } else namespaceLimit
  }

  def allowedMaxPayloadSize: ByteSize = {
    val namespaceLimit = maxPayloadSize getOrElse (ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT_DEFAULT)
    if (namespaceLimit > ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT) {
      ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT
    } else namespaceLimit
  }

  def allowedTruncationSize: ByteSize = {
    val namespaceLimit = truncationSize getOrElse (ActivationEntityLimit.MAX_ACTIVATION_ENTITY_TRUNCATION_LIMIT_DEFAULT)
    if (namespaceLimit > ActivationEntityLimit.MAX_ACTIVATION_ENTITY_TRUNCATION_LIMIT) {
      ActivationEntityLimit.MAX_ACTIVATION_ENTITY_TRUNCATION_LIMIT
    } else namespaceLimit
  }

  def allowedMaxActionConcurrency: Int = {
    val namespaceLimit = maxActionConcurrency.map(_.maxConcurrent) getOrElse (IntraConcurrencyLimit.MAX_CONCURRENT_DEFAULT)
    if (namespaceLimit > IntraConcurrencyLimit.MAX_CONCURRENT) {
      IntraConcurrencyLimit.MAX_CONCURRENT
    } else namespaceLimit
  }

  def allowedMinActionConcurrency: Int = {
    val namespaceLimit = minActionConcurrency.map(_.maxConcurrent) getOrElse (IntraConcurrencyLimit.MIN_CONCURRENT_DEFAULT)
    if (namespaceLimit < IntraConcurrencyLimit.MIN_CONCURRENT) {
      IntraConcurrencyLimit.MIN_CONCURRENT
    } else namespaceLimit
  }

  def allowedMaxActionMemory: ByteSize = {
    val namespaceLimit = maxActionMemory.map(_.toByteSize) getOrElse (MemoryLimit.MAX_MEMORY_DEFAULT)
    if (namespaceLimit > MemoryLimit.MAX_MEMORY) {
      MemoryLimit.MAX_MEMORY
    } else namespaceLimit
  }

  def allowedMinActionMemory: ByteSize = {
    val namespaceLimit = minActionMemory.map(_.toByteSize) getOrElse (MemoryLimit.MIN_MEMORY_DEFAULT)
    if (namespaceLimit < MemoryLimit.MIN_MEMORY) {
      MemoryLimit.MIN_MEMORY
    } else namespaceLimit
  }

  def allowedMaxActionLogs: ByteSize = {
    val namespaceLogsMax = maxActionLogs.map(_.toByteSize) getOrElse (LogLimit.MAX_LOGSIZE_DEFAULT)
    if (namespaceLogsMax > LogLimit.MAX_LOGSIZE) {
      LogLimit.MAX_LOGSIZE
    } else namespaceLogsMax
  }

  def allowedMinActionLogs: ByteSize = {
    val namespaceLimit = minActionLogs.map(_.toByteSize) getOrElse (LogLimit.MIN_LOGSIZE_DEFAULT)
    if (namespaceLimit < LogLimit.MIN_LOGSIZE) {
      LogLimit.MIN_LOGSIZE
    } else namespaceLimit
  }

  def allowedMaxActionTimeout: FiniteDuration = {
    val namespaceLimit = maxActionTimeout.map(_.duration) getOrElse (TimeLimit.MAX_DURATION_DEFAULT)
    if (namespaceLimit > TimeLimit.MAX_DURATION) {
      TimeLimit.MAX_DURATION
    } else namespaceLimit
  }

  def allowedMinActionTimeout: FiniteDuration = {
    val namespaceLimit = minActionTimeout.map(_.duration) getOrElse (TimeLimit.MIN_DURATION_DEFAULT)
    if (namespaceLimit < TimeLimit.MIN_DURATION) {
      TimeLimit.MIN_DURATION
    } else namespaceLimit
  }
}

object UserLimits extends DefaultJsonProtocol {
  val standardUserLimits = UserLimits()
  private implicit val byteSizeSerdes = size.serdes
  implicit val serdes = jsonFormat19(UserLimits.apply)
}

protected[core] case class Namespace(name: EntityName, uuid: UUID)

protected[core] object Namespace extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(Namespace.apply)
}

protected[core] case class Identity(subject: Subject,
                                    namespace: Namespace,
                                    authkey: GenericAuthKey,
                                    rights: Set[Privilege] = Set.empty,
                                    limits: UserLimits = UserLimits.standardUserLimits)

object Identity extends MultipleReadersSingleWriterCache[Option[Identity], DocInfo] with DefaultJsonProtocol {

  private val viewName = WhiskQueries.view(WhiskQueries.dbConfig.subjectsDdoc, "identities").name

  override val cacheEnabled = true
  override val evictionPolicy = WriteTime
  // upper bound for the auth cache to prevent memory pollution by sending
  // malicious namespace patterns
  override val fixedCacheSize = 100000

  implicit val serdes = jsonFormat5(Identity.apply)

  /**
   * Retrieves a key for namespace.
   * There may be more than one key for the namespace, in which case,
   * one is picked arbitrarily.
   */
  def get(datastore: AuthStore, namespace: EntityName)(implicit transid: TransactionId): Future[Identity] = {
    implicit val logger: Logging = datastore.logging
    implicit val ec = datastore.executionContext
    val ns = namespace.asString
    val key = CacheKey(namespace)

    cacheLookup(
      key, {
        list(datastore, List(ns), limit = 1) map { list =>
          list.length match {
            case 1 =>
              Some(rowToIdentity(list.head, ns))
            case 0 =>
              logger.info(this, s"$viewName[$namespace] does not exist")
              None
            case _ =>
              logger.error(this, s"$viewName[$namespace] is not unique")
              throw new IllegalStateException("namespace is not unique")
          }
        }
      }).map(_.getOrElse(throw new NoDocumentException("namespace does not exist")))
  }

  def get(datastore: AuthStore, authkey: BasicAuthenticationAuthKey)(
    implicit transid: TransactionId): Future[Identity] = {
    implicit val logger: Logging = datastore.logging
    implicit val ec = datastore.executionContext

    cacheLookup(
      CacheKey(authkey), {
        list(datastore, List(authkey.uuid.asString, authkey.key.asString)) map { list =>
          list.length match {
            case 1 =>
              Some(rowToIdentity(list.head, authkey.uuid.asString))
            case 0 =>
              logger.info(this, s"$viewName[${authkey.uuid}] does not exist")
              None
            case _ =>
              logger.error(this, s"$viewName[${authkey.uuid}] is not unique")
              throw new IllegalStateException("uuid is not unique")
          }
        }
      }).map(_.getOrElse(throw new NoDocumentException("namespace does not exist")))
  }

  def list(datastore: AuthStore, key: List[Any], limit: Int = 2)(
    implicit transid: TransactionId): Future[List[JsObject]] = {
    datastore.query(
      viewName,
      startKey = key,
      endKey = key,
      skip = 0,
      limit = limit,
      includeDocs = true,
      descending = true,
      reduce = false,
      stale = StaleParameter.No)
  }

  protected[entity] def rowToIdentity(row: JsObject, key: String)(implicit transid: TransactionId, logger: Logging) = {
    row.getFields("id", "value", "doc") match {
      case Seq(JsString(id), JsObject(value), doc) =>
        val limits =
          if (doc != JsNull) Try(doc.convertTo[UserLimits]).getOrElse(UserLimits.standardUserLimits)
          else UserLimits.standardUserLimits
        val subject = Subject(id)
        val JsString(uuid) = value("uuid")
        val JsString(secret) = value("key")
        val JsString(namespace) = value("namespace")
        Identity(
          subject,
          Namespace(EntityName(namespace), UUID(uuid)),
          BasicAuthenticationAuthKey(UUID(uuid), Secret(secret)),
          Privilege.ALL,
          limits)
      case _ =>
        logger.error(this, s"$viewName[$key] has malformed view '${row.compactPrint}'")
        throw new IllegalStateException("identities view malformed")
    }
  }
}
