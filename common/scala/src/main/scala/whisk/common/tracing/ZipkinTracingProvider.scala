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

package whisk.common.tracing

import akka.actor.ActorSystem
import whisk.common.{LoggingMarkers, TransactionId}
import com.github.levkhomich.akka.tracing._
import whisk.spi.{Dependencies, SingletonSpiFactory}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * Zipkin based implementation for TracingProvider
  */
class ZipkinTracingProvider() extends TracingProvider{

  private var trace: Option[TracingExtensionImpl] = None;

  private val traceMap : mutable.Map[Long, TracedRequest] =  TrieMap[Long, TracedRequest]()

  override def init(actorSystem: ActorSystem): Unit = {
    if(! trace.isDefined)
      trace = Some(TracingExtension.apply(actorSystem));
  }

  override def startTrace(serviceName: String, spanName: String, transactionId: TransactionId): Unit = {
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

  override def finish(transactionId: TransactionId): Unit = {
    traceMap.get(transactionId.meta.id) match {
      case Some(tracedRequest) => {
        trace.foreach(tracer => {
          tracer.record(tracedRequest.request, TracingAnnotations.ServerSend)
          traceMap.remove(transactionId.meta.id)
          tracedRequest.parent.map(traceMap.put(transactionId.meta.id, _))
        })
      }
      case _ =>
    }
  }

  override def error(transactionId: TransactionId, t: Throwable): Unit = {
    traceMap.get(transactionId.meta.id) match {
      case Some(tracedRequest) => {
        trace.foreach(tracer => {
          tracer.record(tracedRequest.request, t)
        })
      }
      case _ =>
    }
  }

  override def getTraceContext(transactionId: TransactionId): Option[TraceContext] = {
    traceMap.get(transactionId.meta.id) match {
      case Some(x) => Some(new TraceContext(x.metadata))
      case None => None
    }
  }

  override def setTraceContext(transactionId: TransactionId, context: TraceContext): Unit = {
    traceMap.put(transactionId.meta.id, createTracedRequest(LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING.action, context.metadata.asInstanceOf[SpanMetadata]))
  }

  private def createTracedRequest(spanName: String, metadata: SpanMetadata) : TracedRequest = {

    val req: BasicTraceRequest = new BasicTraceRequest(spanName)
    trace.foreach(tracer => {
      tracer.importMetadata(req, metadata, spanName)
    })
    TracedRequest(req, metadata, None)
  }
}

object ZipkinTracingProvider extends SingletonSpiFactory[TracingProvider] {
  override def apply(dependencies: Dependencies): TracingProvider = new ZipkinTracingProvider
}

case class BasicTraceRequest(override val spanName: String) extends TracingSupport with Serializable

case class TracedRequest(val request: BasicTraceRequest, val metadata: SpanMetadata, var parent: Option[TracedRequest])
