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

package org.apache.openwhisk.core.entity.test

import org.apache.openwhisk.core.database.DocumentFactory
import spray.json._
import org.apache.openwhisk.core.entity._

/**
 * Contains types which represent former versions of database schemas
 * to be able to test migration path
 */
/**
 * Old schema of rules, containing the rules' status in the rule record
 * itself
 */
case class OldWhiskRule(namespace: EntityPath,
                        override val name: EntityName,
                        trigger: EntityName,
                        action: EntityName,
                        status: Status,
                        version: SemVer = SemVer(),
                        publish: Boolean = false,
                        annotations: Parameters = Parameters())
    extends WhiskEntity(name, "rule") {

  def toJson = OldWhiskRule.serdes.write(this).asJsObject

  def toWhiskRule = {
    WhiskRule(
      namespace,
      name,
      FullyQualifiedEntityName(namespace, trigger),
      FullyQualifiedEntityName(namespace, action),
      version,
      publish,
      annotations)
  }
}

object OldWhiskRule
    extends DocumentFactory[OldWhiskRule]
    with WhiskEntityQueries[OldWhiskRule]
    with DefaultJsonProtocol {

  override val collectionName = "rules"
  override implicit val serdes = jsonFormat8(OldWhiskRule.apply)
}

/**
 * Old schema of triggers, not containing a map of ReducedRules
 */
case class OldWhiskTrigger(namespace: EntityPath,
                           override val name: EntityName,
                           parameters: Parameters = Parameters(),
                           limits: TriggerLimits = TriggerLimits(),
                           version: SemVer = SemVer(),
                           publish: Boolean = false,
                           annotations: Parameters = Parameters())
    extends WhiskEntity(name, "trigger") {

  def toJson = OldWhiskTrigger.serdes.write(this).asJsObject

  def toWhiskTrigger = WhiskTrigger(namespace, name, parameters, limits, version, publish, annotations)
}

object OldWhiskTrigger
    extends DocumentFactory[OldWhiskTrigger]
    with WhiskEntityQueries[OldWhiskTrigger]
    with DefaultJsonProtocol {

  override val collectionName = "triggers"
  override implicit val serdes = jsonFormat7(OldWhiskTrigger.apply)
}
