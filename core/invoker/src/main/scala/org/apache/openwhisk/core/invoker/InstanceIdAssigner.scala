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

package org.apache.openwhisk.core.invoker

import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.recipes.shared.SharedCount
import org.apache.curator.retry.RetryUntilElapsed
import org.apache.openwhisk.common.Logging

/**
 * Computes the instanceId for invoker
 *
 * @param connectionString zooKeeper connection string
 */
private[invoker] class InstanceIdAssigner(connectionString: String)(implicit logger: Logging) {

  def getId(name: String): Int = {
    logger.info(this, s"invokerReg: creating zkClient to $connectionString")
    val retryPolicy = new RetryUntilElapsed(5000, 500) // retry at 500ms intervals until 5 seconds have elapsed
    val zkClient = CuratorFrameworkFactory.newClient(connectionString, retryPolicy)
    zkClient.start()
    zkClient.blockUntilConnected()
    logger.info(this, "invokerReg: connected to zookeeper")

    val myIdPath = "/invokers/idAssignment/mapping/" + name
    val assignedId = Option(zkClient.checkExists().forPath(myIdPath)) match {
      case None =>
        // path doesn't exist -> no previous mapping for this invoker
        logger.info(this, s"invokerReg: no prior assignment of id for invoker $name")
        val idCounter = new SharedCount(zkClient, "/invokers/idAssignment/counter", 0)
        idCounter.start()

        def assignId(): Int = {
          val current = idCounter.getVersionedValue()
          if (idCounter.trySetCount(current, current.getValue() + 1)) {
            current.getValue()
          } else {
            assignId()
          }
        }

        val newId = assignId()
        idCounter.close()
        zkClient.create().creatingParentContainersIfNeeded().forPath(myIdPath, BigInt(newId).toByteArray)
        logger.info(this, s"invokerReg: invoker $name was assigned invokerId $newId")
        newId

      case Some(_) =>
        // path already exists -> there is a previous mapping for this invoker we should use
        val rawOldId = zkClient.getData().forPath(myIdPath)
        val oldId = BigInt(rawOldId).intValue
        logger.info(this, s"invokerReg: invoker $name was assigned its previous invokerId $oldId")
        oldId
    }

    zkClient.close()
    assignedId
  }

}
