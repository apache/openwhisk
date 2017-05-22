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
