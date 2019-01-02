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

object MetricNames {
  val activationMetric = "openwhisk_action_activations"
  val coldStartMetric = "openwhisk_action_coldStarts"
  val waitTimeMetric = "openwhisk_action_waitTime"
  val initTimeMetric = "openwhisk_action_initTime"
  val durationMetric = "openwhisk_action_duration"
  val statusMetric = "openwhisk_action_status"
}
