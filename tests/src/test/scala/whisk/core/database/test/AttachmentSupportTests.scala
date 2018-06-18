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

package whisk.core.database.test

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.CompactByteString
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import whisk.core.database.{AttachmentSupport, InliningConfig}
import whisk.core.entity.size._

@RunWith(classOf[JUnitRunner])
class AttachmentSupportTests extends FlatSpec with Matchers with ScalaFutures with WskActorSystem {

  behavior of "Attachment inlining"

  implicit val materializer: Materializer = ActorMaterializer()

  it should "not inline if maxInlineSize set to zero" in {
    val inliner = new TestInliner(InliningConfig(maxInlineSize = 0.KB, chunkSize = 8.KB))
    val bs = CompactByteString("hello world")

    val bytesOrSource = inliner.inlineAndTail(Source.single(bs)).futureValue
    val uri = inliner.uriOf(bytesOrSource, "foo")

    uri shouldBe Uri("test:foo")
  }

  class TestInliner(val inliningConfig: InliningConfig) extends AttachmentSupport {
    override protected[core] implicit val materializer: Materializer = ActorMaterializer()
    override protected def attachmentScheme: String = "test"
  }
}
