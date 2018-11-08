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

package org.apache.openwhisk.core.database.s3
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.database.s3.S3AttachmentStoreProvider.S3Config
import org.apache.openwhisk.core.entity.WhiskEntity

@RunWith(classOf[JUnitRunner])
class S3WithPrefixTests extends S3AttachmentStoreMinioTests {
  override protected val bucketPrefix: String = "master"

  behavior of "S3Config"

  it should "work with none prefix" in {
    val config = S3Config("foo", None)
    config.prefixFor[WhiskEntity] shouldBe "whiskentity"
  }

  it should "work with optional prefix" in {
    val config = S3Config("foo", Some("bar"))
    config.prefixFor[WhiskEntity] shouldBe "bar/whiskentity"
  }
}
