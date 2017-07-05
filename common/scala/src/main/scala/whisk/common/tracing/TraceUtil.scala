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

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.Map
import akka.actor.ActorSystem
import com.github.levkhomich.akka.tracing._
import whisk.common.{LoggingMarkers, TransactionId}

/**
  * Trace Utility Class which provides all methods to trace a given service.
  */
object TraceUtil{

  private lazy val trace: TracingExtensionImpl = traceVar;

  private var traceVar: TracingExtensionImpl = null;

  private val traceMap : Map[Long, TracedRequest] =  Map[Long, TracedRequest]()

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
  def startTrace(serviceName: String, spanName: String, transactionId: TransactionId): Unit = {
    traceMap.get(transactionId.meta.id) match {
      case Some(tracedRequest) => {
        startChildTrace(serviceName, spanName, tracedRequest, transactionId)
      }
      case None => {
        var tracedRequest = createTracedRequest(spanName)
        var metadata = trace.sample(tracedRequest.request, serviceName)
        if (metadata != None) {
          trace.start(tracedRequest.request, serviceName)
          tracedRequest = new TracedRequest(tracedRequest.request, null, metadata)
          traceMap.put(transactionId.meta.id, tracedRequest)
        }
      }
    }

  }

  /**
    * Start a Child Trace
    * @param serviceName Name of service to be traced
    * @param parent Parent TracedRequest to continue span propagation and create span hierarchy.
    * @param transactionId transactionId to which this Trace belongs.
    * @return TracedRequest which provides details about current service being traced.
    */
  def startChildTrace(serviceName: String, spanName: String, parent: TracedRequest, transactionId: TransactionId): Unit = {
    val tracedReq: TracedRequest = createTracedRequest(spanName)
    var metadata =  trace.sample(tracedReq.request, serviceName)
    if(metadata.isDefined){
      trace.createChild(tracedReq.request, parent.request)
      trace.start(tracedReq.request, serviceName)
      val tracedRequest = new TracedRequest(tracedReq.request, Some(parent), metadata)
      traceMap.put(transactionId.meta.id, tracedRequest)
    }
  }

  /**
    * Finish a Trace associated with given transactionId.
    * @param transactionId
    */
  def finish(transactionId: TransactionId): Unit = {
    traceMap.get(transactionId.meta.id) match {
      case Some(tracedRequest) => {
        trace.record(tracedRequest.request, TracingAnnotations.ServerSend)
        traceMap.remove(transactionId.meta.id)
        if(tracedRequest.parent.isDefined)
          traceMap.put(transactionId.meta.id, tracedRequest.parent.get)
      }
      case _ =>
    }
  }

  def error(transactionId: TransactionId, t: Throwable) : Unit = {
    val tracedRequest = traceMap.get(transactionId.meta.id)
    if(tracedRequest.isDefined){
      trace.record(tracedRequest.get.request, t)
    }
  }

  private def createTracedRequest(spanName: String, metadata: Option[SpanMetadata] = None) : TracedRequest = {
    val headers: Map[String, Integer] = Map[String, Integer]()
    headers.put("id", requestCounter.getAndIncrement())
    val req: BasicTraceRequest = new BasicTraceRequest(headers, spanName)

    if(metadata.isDefined)
      trace.importMetadata(req, metadata.get, spanName)

    return TracedRequest(req, null, metadata)
  }

  def getTraceMetadata(transactionId: TransactionId): Option[SpanMetadata] = {
    traceMap.get(transactionId.meta.id) match {
      case Some(tracedRequest) => {
       return tracedRequest.metadata
      }
      case _ =>
    }
    return None
  }

  def setTracedRequestForTrasactionId(transactionId: TransactionId, metadata: Option[SpanMetadata]) = {

    if(metadata.isDefined)
      traceMap.put(transactionId.meta.id, TraceUtil.createTracedRequest(LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING.action, metadata))
  }
}

case class BasicTraceRequest(val headers: Map[String, Integer], override val spanName: String) extends TracingSupport with Serializable

case class TracedRequest(val request: BasicTraceRequest, val parent: Option[TracedRequest], val metadata: Option[SpanMetadata])
