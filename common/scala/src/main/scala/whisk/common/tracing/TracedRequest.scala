/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common.tracing

import com.github.levkhomich.akka.tracing.SpanMetadata

/**
  * This class encapsulates all required information related to a request being traced.
  * Current service details, Its Parent Request and SpanMetadata required to propagate trace information.
  */
class TracedRequest(val request: BasicTraceRequest, val parent: TracedRequest, val metadata: Option[SpanMetadata]) {

  def getParent() : TracedRequest = {
    return parent
  }


  def getRequest() : BasicTraceRequest = {
    return request
  }

  def getMetadata() : Option[SpanMetadata] = {
    return metadata
  }

}
