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

package org.apache.openwhisk.core.cli

object CommandMessages {
  val subjectBlocked = "The subject you want to edit is blocked"
  val namespaceExists = "Namespace already exists"
  val shortName = "Subject name must be at least 5 characters"
  val invalidUUID = "authorization id is not a valid UUID"
  val shortKey = "authorization key must be at least 64 characters long"

  val subjectMissing = "Subject to delete not found"

  def namespaceMissing(ns: String, u: String) = s"Namespace '$ns' does not exist for '$u'"
  val namespaceDeleted = "Namespace deleted"
  val subjectDeleted = "Subject deleted"

  def namespaceMissing(ns: String) = s"no identities found for namespace  '$ns'"
  def blocked(subject: String) = s"'$subject' blocked successfully"
  def unblocked(subject: String) = s"'$subject' unblocked successfully"
  def subjectMissing(subject: String) = s"'$subject missing"

  def limitsSuccessfullyUpdated(namespace: String) = s"Limits successfully updated for '$namespace'"
  def limitsSuccessfullySet(namespace: String) = s"Limits successfully set for '$namespace'"
  val defaultLimits = "No limits found, default system limits apply"

  def limitsNotFound(namespace: String) = s"Limits not found for '$namespace'"
  val limitsDeleted = s"Limits deleted"

}
