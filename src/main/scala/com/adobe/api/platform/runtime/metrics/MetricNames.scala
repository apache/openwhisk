/*
Copyright 2018 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
 */

package com.adobe.api.platform.runtime.metrics

trait MetricNames {
  val actionNamespace = "namespace"
  val initiatorNamespace = "initiator"
  val actionName = "action"
  val actionStatus = "status"
  val actionMemory = "memory"
  val actionKind = "kind"

  def activationMetric: String
  def coldStartMetric: String
  def waitTimeMetric: String
  def initTimeMetric: String
  def durationMetric: String
  def statusMetric: String

  def concurrentLimitMetric: String
  def timedLimitMetric: String
}
