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

package org.apache.openwhisk.core.scheduler.queue

import akka.actor.ActorSystem
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.entity.WhiskActionMetaData

import scala.concurrent.Future

object NoopDurationCheckerProvider extends DurationCheckerProvider {
  override def instance(actorSystem: ActorSystem, log: Logging): NoopDurationChecker = {
    implicit val as: ActorSystem = actorSystem
    implicit val logging: Logging = log
    new NoopDurationChecker()
  }
}

object NoopDurationChecker {
  implicit val serde = new ElasticSearchDurationCheckResultFormat()
}

class NoopDurationChecker extends DurationChecker {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def checkAverageDuration(invocationNamespace: String, actionMetaData: WhiskActionMetaData)(
    callback: DurationCheckResult => DurationCheckResult): Future[DurationCheckResult] = {
    Future {
      DurationCheckResult(Option.apply(0), 0, 0)
    }
  }
}
