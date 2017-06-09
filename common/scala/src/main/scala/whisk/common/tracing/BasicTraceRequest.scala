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

import com.github.levkhomich.akka.tracing.TracingSupport
import java.util._

/**
  * Tracing support class which encapsulates Traced Service details
  */
class BasicTraceRequest(val headers: Map[String, Integer], val payload: String) extends TracingSupport with Serializable{

  override def spanName: String =
    payload

  def getHeaders: Map[String, Integer] = {
    return headers
  }

  def getPayload: String = {
    return payload
  }

  override def hashCode(): Int = {
    val prime: Int = 31
    var result: Int = 1
    result = prime * result + (if ((headers == null)) 0 else headers.hashCode)
    result = prime * result + (if ((payload == null)) 0 else payload.hashCode)
    result
  }

  override def equals(obj: Any): Boolean = {
    if (this == obj) true
    if (obj == null) false
    if (getClass != obj.getClass) false
    val other: BasicTraceRequest = obj.asInstanceOf[BasicTraceRequest]
    if (headers == null) {
      if (other.headers != null) false
    } else if (headers != other.headers) false
    if (payload == null) {
      if (other.payload != null) false
    } else if (payload != other.payload) false
    true
  }

}
