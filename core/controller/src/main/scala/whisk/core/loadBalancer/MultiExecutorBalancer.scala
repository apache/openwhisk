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

package whisk.core.loadBalancer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import spray.json.JsObject
import whisk.core.entity.UUID

/**
 * A LoadBalancer that delegates execution to ActivationExecutors
 */
abstract class MultiExecutorBalancer extends LoadBalancer {

    protected val loadBalancerData = new LoadBalancerData()

    def activeActivationsFor(namespace: UUID) = loadBalancerData.activationCountOn(namespace)

    def totalActiveActivations = loadBalancerData.totalActivationCount

    def executors: Seq[ActivationExecutor]

    implicit val ec:ExecutionContext
    /**
     * Return a message indicating the health of all the executors
     * @return a Future[JsObject] representing the health response that will be sent to the client
     */
    override def healthStatus: Future[JsObject] = {
        val executorStatuses = executors.map(e => (e.name -> e.healthStatus.map(s => s) ))
        val allStatues = executorStatuses.map(f => (f._1 -> f._2.map(h =>  h )))

        Future.traverse(allStatues) {
            case (k, fv) => fv.map(k -> _)
        }.map(_.toMap).map(s => JsObject(s))

    }
}
