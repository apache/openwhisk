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

package org.apache.openwhisk.core.database.mongodb

import java.nio.ByteBuffer

import akka.Done
import akka.stream.{Attributes, IOResult, Inlet, SinkShape}
import akka.stream.scaladsl.Sink
import akka.stream.stage.{AsyncCallback, GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import akka.util.ByteString
import org.mongodb.scala.Completed
import org.mongodb.scala.gridfs.{AsyncOutputStream}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class MongoDBAsyncStreamSink(stream: AsyncOutputStream)(implicit ec: ExecutionContext)
    extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[IOResult]] {
  val in: Inlet[ByteString] = Inlet("AsyncStream.in")

  override val shape: SinkShape[ByteString] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val ioResultPromise = Promise[IOResult]()
    val logic = new GraphStageLogic(shape) with InHandler {
      handler =>
      var buffers: Iterator[ByteBuffer] = Iterator()
      var writeCallback: AsyncCallback[Try[Int]] = _
      var closeCallback: AsyncCallback[Try[Completed]] = _
      var position: Int = _
      var writeDone = Promise[Completed]

      setHandler(in, this)

      override def preStart(): Unit = {
        //close operation is async and thus requires the stage to remain open
        //even after all data is read
        setKeepGoing(true)
        writeCallback = getAsyncCallback[Try[Int]](handleWriteResult)
        closeCallback = getAsyncCallback[Try[Completed]](handleClose)
        pull(in)
      }

      override def onPush(): Unit = {
        buffers = grab(in).asByteBuffers.iterator
        writeDone = Promise[Completed]
        writeNextBufferOrPull()
      }

      override def onUpstreamFinish(): Unit = {
        //Work done perform close
        //Using async "blessed" callback does not work at this stage so
        // need to invoke as normal callback
        //TODO Revisit this

        //write of ByteBuffers from ByteString is an async operation. For last push
        //the write operation may involve multiple async callbacks and by that time
        //onUpstreamFinish may get invoked. So to ensure that close operation is performed
        //"after" the last push writes are done we rely on writeDone promise
        //and schedule the close on its completion
        writeDone.future.onComplete(_ => stream.close().head().onComplete(handleClose))
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        fail(ex)
      }

      private def handleWriteResult(bytesWrittenOrFailure: Try[Int]): Unit = bytesWrittenOrFailure match {
        case Success(bytesWritten) =>
          position += bytesWritten
          writeNextBufferOrPull()
        case Failure(failure) => fail(failure)
      }

      private def handleClose(completed: Try[Completed]): Unit = completed match {
        case Success(Completed()) =>
          completeStage()
          ioResultPromise.trySuccess(IOResult(position, Success(Done)))
        case Failure(failure) =>
          fail(failure)
      }

      private def writeNextBufferOrPull(): Unit = {
        if (buffers.hasNext) {
          stream.write(buffers.next()).head().onComplete(writeCallback.invoke)
        } else {
          writeDone.trySuccess(Completed())
          pull(in)
        }
      }

      private def fail(failure: Throwable) = {
        failStage(failure)
        ioResultPromise.trySuccess(IOResult(position, Failure(failure)))
      }

    }
    (logic, ioResultPromise.future)
  }
}

object MongoDBAsyncStreamSink {
  def apply(stream: AsyncOutputStream)(implicit ec: ExecutionContext): Sink[ByteString, Future[IOResult]] = {
    Sink.fromGraph(new MongoDBAsyncStreamSink(stream))
  }
}
