package whisk.common.tracing

import com.github.levkhomich.akka.tracing.TracingSupport
import java.util._

/**
  * Tracing support class which encapsulates Traced Service details
  */
class BasicTraceRequest(val headers: Map[String, Integer], val payload: String) extends TracingSupport with Serializable{

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