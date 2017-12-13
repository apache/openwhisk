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

// TO BE TAKEN OUT AFTER ALPAKKA 0.15 RELEASE

/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.file.scaladsl

import java.nio.file.{OpenOption, Path, StandardOpenOption}

import akka.Done
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.fusing.MapAsync.{Holder, NotYetThere}
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object LogRotatorSink {
  def apply(functionGeneratorFunction: () => ByteString => Option[Path],
            fileOpenOptions: Set[OpenOption] = Set(StandardOpenOption.APPEND, StandardOpenOption.CREATE))
    : Sink[ByteString, Future[Done]] =
    Sink.fromGraph(new LogRotatorSink(functionGeneratorFunction, fileOpenOptions))
}

final private[scaladsl] class LogRotatorSink(functionGeneratorFunction: () => ByteString => Option[Path],
                                             fileOpenOptions: Set[OpenOption])
    extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Done]] {

  val in = Inlet[ByteString]("FRotator.in")
  override val shape = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]()
    val logic = new GraphStageLogic(shape) {
      val pathGeneratorFunction: ByteString => Option[Path] = functionGeneratorFunction()
      var sourceOut: SubSourceOutlet[ByteString] = _
      var fileSinkCompleted: Seq[Future[IOResult]] = Seq.empty
      val decider =
        inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)

      def failThisStage(ex: Throwable): Unit =
        if (!promise.isCompleted) {
          if (sourceOut != null) {
            sourceOut.fail(ex)
          }
          cancel(in)
          promise.failure(ex)
        }

      def generatePathOrFailPeacefully(data: ByteString): Option[Path] = {
        var ret = Option.empty[Path]
        try {
          ret = pathGeneratorFunction(data)
        } catch {
          case ex: Throwable =>
            failThisStage(ex)
        }
        ret
      }

      def fileSinkFutureCallbackHandler(future: Future[IOResult])(h: Holder[IOResult]): Unit =
        h.elem match {
          case Success(IOResult(_, Failure(ex))) if decider(ex) == Supervision.Stop =>
            promise.failure(ex)
          case Success(x) if fileSinkCompleted.size == 1 && fileSinkCompleted.head == future =>
            promise.trySuccess(Done)
            completeStage()
          case x: Success[IOResult] =>
            fileSinkCompleted = fileSinkCompleted.filter(_ != future)
          case Failure(ex) =>
            failThisStage(ex)
          case _ =>
        }

      //init stage where we are waiting for the first path
      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val data = grab(in)
            val pathO = generatePathOrFailPeacefully(data)
            pathO.fold(if (!isClosed(in)) pull(in))(switchPath(_, data))
          }

          override def onUpstreamFinish(): Unit =
            completeStage()

          override def onUpstreamFailure(ex: Throwable): Unit =
            failThisStage(ex)
        })

      //we must pull the first element cos we are a sink
      override def preStart(): Unit = {
        super.preStart()
        pull(in)
      }

      def futureCB(newFuture: Future[IOResult]) =
        getAsyncCallback[Holder[IOResult]](fileSinkFutureCallbackHandler(newFuture))

      //we recreate the tail of the stream, and emit the data for the next req
      def switchPath(path: Path, data: ByteString): Unit = {
        val prevOut = Option(sourceOut)

        sourceOut = new SubSourceOutlet[ByteString]("FRotatorSource")
        sourceOut.setHandler(new OutHandler {
          override def onPull(): Unit = {
            sourceOut.push(data)
            switchToNormalMode()
          }
        })
        val newFuture = Source
          .fromGraph(sourceOut.source)
          .runWith(FileIO.toPath(path, fileOpenOptions))(interpreter.subFusingMaterializer)

        fileSinkCompleted = fileSinkCompleted :+ newFuture

        val holder = new Holder[IOResult](NotYetThere, futureCB(newFuture))

        newFuture.onComplete(holder)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)

        prevOut.foreach(_.complete())
      }

      //we change path if needed or push the grabbed data
      def switchToNormalMode(): Unit = {
        setHandler(
          in,
          new InHandler {
            override def onPush(): Unit = {
              val data = grab(in)
              val pathO = generatePathOrFailPeacefully(data)
              pathO.fold(sourceOut.push(data))(switchPath(_, data))
            }

            override def onUpstreamFinish(): Unit = {
              implicit val executionContext: ExecutionContext =
                akka.dispatch.ExecutionContexts.sameThreadExecutionContext
              promise.completeWith(Future.sequence(fileSinkCompleted).map(_ => Done))
              sourceOut.complete()
            }

            override def onUpstreamFailure(ex: Throwable): Unit =
              failThisStage(ex)
          })
        sourceOut.setHandler(new OutHandler {
          override def onPull(): Unit =
            pull(in)
        })
      }
    }
    (logic, promise.future)
  }

}
