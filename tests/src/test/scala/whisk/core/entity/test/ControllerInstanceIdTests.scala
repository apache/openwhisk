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

package whisk.core.entity.test

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import whisk.core.entity.ControllerInstanceId

@RunWith(classOf[JUnitRunner])
class ControllerInstanceIdTests extends FlatSpec with Matchers {

  behavior of "StringInstanceId"

  it should "strip unusable characters when creating a topic name" in {
    val id = ControllerInstanceId("  12  34&&^^%%5$ $7890._-123  ")
    id.asString shouldBe "123457890._-123"
  }

  it should "throw an exception when the topic name length exceeds the limit" in {
    val maxLen = 230
    //229 chars is fine
    ControllerInstanceId("1" * (maxLen - 1)) //should be fine
    //230 chars is too long...
    a[IllegalArgumentException] should be thrownBy ControllerInstanceId("1" * 230)
  }

}
