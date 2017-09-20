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

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import java.util.Map.Entry
import whisk.core.connector.ActivationMessage
import whisk.core.entity.ExecutableWhiskAction
import scala.collection.JavaConverters._
import whisk.common.Logging
trait LoadBalancerResolver {
  def loadBalancer(action: ExecutableWhiskAction, msg: ActivationMessage): Option[LoadBalancer]
}

/**
 * A LoadBalancerResolver that returns the single available LoadBalancer impl
 * @param loadBalancers
 */
class SingleLoadBalancerResolver(loadBalancers: Seq[LoadBalancer]) extends LoadBalancerResolver {
  require(loadBalancers.size == 1)
  val singleLoadBalancer = Some(loadBalancers.head)
  override def loadBalancer(action: ExecutableWhiskAction, msg: ActivationMessage) = singleLoadBalancer
}

/**
 * A LoadBalancer that returns the LoadBalancer configured for a specific action kind, or else a default
 * @param loadBalancers
 */
class KindBasedLoadBalancerResolver(loadBalancers: Seq[LoadBalancer])(implicit logging: Logging)
    extends LoadBalancerResolver {
  val config = ConfigFactory.load()

  val lbMap = loadBalancers.map(lb => (lb.getClass.getName -> lb)).toMap

  val defaultLb = config.getString("whisk.loadbalancer.kindbased.default")

  val kindMap = (for {
    kindMapping: ConfigObject <- config.getObjectList("whisk.loadbalancer.kindbased.kinds").asScala
    entry: Entry[String, ConfigValue] <- kindMapping.entrySet().asScala
  } yield (entry.getKey, entry.getValue.unwrapped().toString)).toMap

  logging.info(this, s"default load balancer is ${defaultLb}")
  kindMap.foreach(k => logging.info(this, s" kind ${k._1} is mapped to load balancer ${k._2}"))
  override def loadBalancer(action: ExecutableWhiskAction, msg: ActivationMessage) =
    lbMap.get(kindMap.getOrElse(action.exec.kind, defaultLb))

}
