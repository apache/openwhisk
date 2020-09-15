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
import akka.stream.SourceShape
import akka.stream.Attributes
import akka.stream.Outlet
import akka.stream.IOResult
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.AsyncCallback
import akka.util.ByteString
import org.mongodb.scala.Completed
import org.mongodb.scala.gridfs.AsyncInputStream

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success
import scala.util.Try
import scala.util.Failure

class MongoDBAsyncStreamSource(stream: AsyncInputStream, chunkSize: Int)(implicit ec: ExecutionContext)
    extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[IOResult]] {
  require(chunkSize > 0, "chunkSize must be greater than 0")
  val out: Outlet[ByteString] = Outlet("AsyncStream.out")

  override val shape: SourceShape[ByteString] = SourceShape(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val ioResultPromise = Promise[IOResult]()
    val logic = new GraphStageLogic(shape) with OutHandler {
      handler =>
      val buffer = ByteBuffer.allocate(chunkSize)
      var readCallback: AsyncCallback[Try[Int]] = _
      var closeCallback: AsyncCallback[Try[Completed]] = _
      var position: Int = _

      setHandler(out, this)

      override def preStart(): Unit = {
        readCallback = getAsyncCallback[Try[Int]](handleBufferRead)
        closeCallback = getAsyncCallback[Try[Completed]](handleClose)
      }

      override def onPull(): Unit = {
        stream.read(buffer).head().onComplete(readCallback.invoke)
      }

      private def handleBufferRead(bytesReadOrFailure: Try[Int]): Unit = bytesReadOrFailure match {
        case Success(bytesRead) if bytesRead >= 0 =>
          buffer.flip
          push(out, ByteString.fromByteBuffer(buffer))
          buffer.clear
          position += bytesRead
        case Success(_) =>
          stream.close().head().onComplete(closeCallback.invoke) //Work done perform close
        case Failure(failure) =>
          fail(failure)
      }

      private def handleClose(completed: Try[Completed]): Unit = completed match {
        case Success(Completed()) =>
          completeStage()
          ioResultPromise.trySuccess(IOResult(position, Success(Done)))
        case Failure(failure) =>
          fail(failure)
      }

      private def fail(failure: Throwable) = {
        failStage(failure)
        ioResultPromise.trySuccess(IOResult(position, Failure(failure)))
      }
    }
    (logic, ioResultPromise.future)
  }
}

object MongoDBAsyncStreamSource {
  def apply(stream: AsyncInputStream, chunkSize: Int = 512 * 1024)(
    implicit ec: ExecutionContext): Source[ByteString, Future[IOResult]] = {
    Source.fromGraph(new MongoDBAsyncStreamSource(stream, chunkSize))
  }
}
