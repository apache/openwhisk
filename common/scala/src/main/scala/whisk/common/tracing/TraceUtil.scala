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

import java.util
import java.util.{HashMap, Map}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.github.levkhomich.akka.tracing._
import whisk.common.{LoggingMarkers, TransactionId}

/**
  * Trace Utility Class which provides all methods to trace a given service.
  */
object TraceUtil{

    private lazy val trace: TracingExtensionImpl = traceVar;

    private var traceVar: TracingExtensionImpl = null;

    private val traceMap : Map[Long, TracedRequest] = new util.HashMap[Long, TracedRequest]()

    var requestCounter: AtomicInteger = new AtomicInteger(1);

    def init (actorSystem: ActorSystem): TracingExtensionImpl = {

      if(traceVar == null)
        traceVar = TracingExtension.apply(actorSystem);

      return trace;
    }

  /**
    * Start a Trace for given service.
    * @param serviceName Name of Service to be traced
    * @param transactionId transactionId to which this Trace belongs.
    * @return TracedRequest which provides details about current service being traced.
    */
  def startTrace(serviceName: String, transactionId: TransactionId): Unit = {
    var tracedRequest: TracedRequest = traceMap.get(transactionId.meta.id)
    if(tracedRequest == null){
      var tracedRequest = createTracedRequest(serviceName)
      var metadata = trace.sample(tracedRequest.getRequest(), serviceName)
      if(metadata != None){
        trace.start(tracedRequest.getRequest(), serviceName)
        tracedRequest = new TracedRequest(tracedRequest.getRequest(), null, metadata)
        traceMap.put(transactionId.meta.id, tracedRequest)
      }
    }
    else
      startChildTrace(serviceName, tracedRequest, transactionId)
  }

  /**
    * Start a Child Trace
    * @param serviceName Name of service to be traced
    * @param parent Parent TracedRequest to continue span propagation and create span hierarchy.
    * @param transactionId transactionId to which this Trace belongs.
    * @return TracedRequest which provides details about current service being traced.
    */
  def startChildTrace(serviceName: String, parent: TracedRequest, transactionId: TransactionId): Unit = {
    val request: TracedRequest = createTracedRequest(serviceName)
    var metadata =  trace.sample(request.getRequest(), serviceName)
    if(metadata != None){
      trace.createChild(request.getRequest(), parent.getRequest())
      trace.start(request.getRequest(), serviceName)
      val tracedRequest = new TracedRequest(request.getRequest(), parent, metadata)
      traceMap.put(transactionId.meta.id, tracedRequest)
    }

  }

  /**
    * Finish a Trace associated with given transactionId.
    * @param transactionId
    */
  def finish(transactionId: TransactionId): Unit = {
    val request: TracedRequest = traceMap.get(transactionId.meta.id)
    if(request != null){
      trace.record(request.getRequest(), TracingAnnotations.ServerSend)
      if(request.getParent() == null)
        traceMap.remove(transactionId.meta.id)
      else
        traceMap.put(transactionId.meta.id, request.getParent())
    }
  }

  def error(transactionId: TransactionId, t: Throwable) : Unit = {
    val request: TracedRequest = traceMap.get(transactionId.meta.id)
    if(request != null){
      trace.record(request.getRequest(), t)
    }
  }

  private def createTracedRequest(serviceName: String, metadata: Option[SpanMetadata] = None) : TracedRequest = {
    val headers: Map[String, Integer] = new HashMap[String, Integer]
    headers.put("id", requestCounter.getAndIncrement())
    val req: BasicTraceRequest = new BasicTraceRequest(headers, serviceName)

    if(metadata != None)
      trace.importMetadata(req, metadata.get, serviceName)

    return new TracedRequest(req, null, metadata)
  }

  def getTracedRequestForTrasactionId(transactionId: TransactionId): TracedRequest = {
    return traceMap.get(transactionId.meta.id)
  }

  def setTracedRequestForTrasactionId(transactionId: TransactionId, metadata: Option[SpanMetadata]) = {

    if(metadata != None)
      traceMap.put(transactionId.meta.id, TraceUtil.createTracedRequest(LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING.getServiceName(), metadata))
  }
}
