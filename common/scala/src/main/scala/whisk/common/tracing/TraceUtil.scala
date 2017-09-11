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

import scala.collection.mutable
import scala.collection.concurrent.TrieMap
import akka.actor.ActorSystem
import com.github.levkhomich.akka.tracing._
import whisk.common.{LoggingMarkers, TransactionId}


/**
  * Trace Utility Class which provides all methods to trace a given service.
  */
object TraceUtil{

  private var trace: Option[TracingExtensionImpl] = None;

  private val traceMap : mutable.Map[Long, TracedRequest] =  TrieMap[Long, TracedRequest]()

  var requestCounter: AtomicInteger = new AtomicInteger(1);

  def init (actorSystem: ActorSystem): Unit = {

    if(! trace.isDefined)
      trace = Some(TracingExtension.apply(actorSystem));
  }

  /**
    * Start a Trace for given service.
    * @param serviceName Name of Service to be traced
    * @param transactionId transactionId to which this Trace belongs.
    * @return TracedRequest which provides details about current service being traced.
    */
  def startTrace(serviceName: String, spanName: String, transactionId: TransactionId): Unit = {
    trace.foreach(tracer => {
      val req: BasicTraceRequest = new BasicTraceRequest(spanName)
      var metadata = tracer.sample(req, serviceName)
      metadata.foreach(mData => {
        val tracedReq = createTracedRequest(spanName, mData)
        traceMap.get(transactionId.meta.id) match {
          case Some(parentReq) => {
            //create a child trace
            tracer.createChild(req, parentReq.request)
            tracedReq.parent = Some(parentReq)
          }
          case None =>
          }
        tracer.start(req, serviceName)
        traceMap.put(transactionId.meta.id, tracedReq)
      })
    })
  }

  /**
    * Finish a Trace associated with given transactionId.
    * @param transactionId
    */
  def finish(transactionId: TransactionId): Unit = {
    traceMap.get(transactionId.meta.id) match {
      case Some(tracedRequest) => {
        trace.foreach(tracer => {
          tracer.record(tracedRequest.request, TracingAnnotations.ServerSend)
          traceMap.remove(transactionId.meta.id)
          tracedRequest.parent.map(traceMap.put(transactionId.meta.id, _))
//          if(tracedRequest.parent.isDefined)
//            traceMap.put(transactionId.meta.id, tracedRequest.parent.get)
        })

      }
      case _ =>
    }
  }

  def error(transactionId: TransactionId, t: Throwable) : Unit = {
    traceMap.get(transactionId.meta.id) match {
      case Some(tracedRequest) => {
        trace.foreach(tracer => {
          tracer.record(tracedRequest.request, t)
        })
      }
      case _ =>
    }
  }

  private def createTracedRequest(spanName: String, metadata: SpanMetadata) : TracedRequest = {

    val req: BasicTraceRequest = new BasicTraceRequest(spanName)
    trace.foreach(tracer => {
        tracer.importMetadata(req, metadata, spanName)
    })
    TracedRequest(req, metadata, None)
  }

  def getTraceMetadata(transactionId: TransactionId): Option[SpanMetadata] = {
    traceMap.get(transactionId.meta.id).map(_.metadata)
  }

  def setTracedRequestForTrasactionId(transactionId: TransactionId, metadata: SpanMetadata) = {
      traceMap.put(transactionId.meta.id, TraceUtil.createTracedRequest(LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING.action, metadata))
  }
}

case class BasicTraceRequest(override val spanName: String) extends TracingSupport with Serializable

case class TracedRequest(val request: BasicTraceRequest, val metadata: SpanMetadata, var parent: Option[TracedRequest])
