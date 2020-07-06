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

package org.apache.openwhisk.core.database.azblob

import com.azure.storage.common.policy.RetryPolicyType
import org.apache.openwhisk.core.ConfigKeys
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import pureconfig._
import pureconfig.generic.auto._

@RunWith(classOf[JUnitRunner])
class AzureBlobConfigTest extends FlatSpec with Matchers {

  behavior of "AzureBlobConfig"
  it should "use valid defaults for retry option" in {
    val config: AzBlobRetryConfig = loadConfigOrThrow[AzBlobRetryConfig](ConfigKeys.azBlob + ".retry-config")
    //make sure retry type is set to FIXED
    config.retryPolicyType shouldBe RetryPolicyType.FIXED
    //make sure optional secondaryHost is not set
    config.secondaryHost shouldBe None

  }
}
