/*
 * Copyright 2015-2017 IBM Corporation
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

package whisk.common

import scala.collection.JavaConversions._

import io.opentracing.contrib.tracerresolver.TracerResolver
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer
import io.opentracing.Span
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapExtractAdapter
import io.opentracing.propagation.TextMapInjectAdapter

object Tracing {

  val tracerOption: Option[Tracer] = initTracer()
  val LOGGING_ENABLED = false

  /**
    * There is no need to call this method explicitly, using Tracing.startSpan and Tracing.endSpan
    * directly will just work.
    *
    * @return the tracer implementation that is the entry point to the open-tracing api
    */
  def initTracer(): Option[Tracer] = {
    // get and register the tracer if the global tracer hasn't been registered
    val existingTracerOption = Option(!GlobalTracer.isRegistered)
      .filter(identity)
      .flatMap(_ => Option(TracerResolver.resolveTracer))
    existingTracerOption foreach GlobalTracer.register

    // if the previous option is empty, get the global tracer as fallback method
    Option(existingTracerOption.getOrElse(GlobalTracer.get))
  }

  /**
    * Metadata used for the span creation.
    *
    * @param action name of the span (most prominent piece of information)
    * @param path path of the package
    * @param user name of the current user who invokes the action
    * @param revision revision of the action
    * @param version version of the action
    */
  case class SpanMetadata(action: String, path: String, user: String, revision: String, version: String) {
    override def toString: String = {
      s"SPAN[action=$action, path=$path, user=$user, revision=$revision, version=$version]"
    }
  }

  /**
    * Starts the tracing and returns the span that can be later on closed by Tracing.endSpan(spanOption).
    *
    * Semantics is as follows:
    *          1. when starting a primitive action/span don't care about parentOption and carrierOption
    *          2. when starting a primitive action/span that is child of another action/span, pass the parentOption
    *             with the hash map of ids and the implementation will make the child-parent relationship
    *          3. when starting an action/span that can have children, pass the empty mutable Map [String, String]
    *             in the carrierOption argument and later on continue with point (2), but use this map as a parentOption
    *          4. combination of (2) and (3) i.e. starting a sequence that is itself part of another sequence. In this case
    *             pass both parentOption (as reference for the parent) and carrierOption (for the future use by children)
    *
    *  NOTE: make sure, you end the span with the endSpan method once the encapsulating action is done
    *
    * @param spanMetadata metadata needed for creating the span, they will be used as tags on that new span
    * @param parentOption if the parent option is not empty the new span will be started as a child span of the parent one
    * @param carrierOption if the carrier option is not empty, but it contains the empty mutable map, the tracer implementation
    *                      fills (Tracer.inject) the set of specific ids using text map method so that later on it can be extracted.
    * @return the created span
    */
  def startSpan(spanMetadata: SpanMetadata,
                parentOption: Option[Map[String, String]] = None,
                carrierOption: Option[scala.collection.mutable.HashMap[String, String]] = None)(implicit logging: Logging): Option[Span] = {

    if (LOGGING_ENABLED) {
      logging.info(this, s"newSpan: $spanMetadata")
      logging.info(this, s"parentSpan: $parentOption")
      logging.info(this, s"carrierForNewSpan: $carrierOption")
    }

    tracerOption.map(tracer => {
      val spanBuilder = tracer.buildSpan(spanMetadata.action)

      // add reference to parrent if there is any and start the span
      val span = parentOption.map(parent => {
        val parentSpan = tracer.extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(parent))
        spanBuilder.asChildOf(parentSpan)
      }).getOrElse(spanBuilder).startManual

      // inject the context in case it's the child of high-lvl action (sequence)
      carrierOption.map(carrier => {
        tracer.inject(span.context, Format.Builtin.TEXT_MAP, new TextMapInjectAdapter(carrier))
      })

      Tags.COMPONENT.set(span, "openwhisk")

      // general message data
      span.setTag("user", spanMetadata.user)
      span.setTag("revision", spanMetadata.revision)

      // action data
      span.setTag("action", spanMetadata.action)
      span.setTag("version", spanMetadata.version)
      span.setTag("path", spanMetadata.path)
      span
    });
  }

  /**
    * Closes the passed span, this method is basically dual to the startSpan method
    *
    * @param spanOption span that is to be ended/finished
    */
  def endSpan(spanOption: Option[Span]): Unit = {
    spanOption.foreach(_.finish)
  }

}
