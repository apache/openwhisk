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

package org.apache.openwhisk.core.database.test.behavior

import common.TestUtils

import scala.util.{Failure, Success, Try}

object ArtifactStoreTestUtil {

  def storeAvailable(storeAvailableCheck: Try[Any]): Boolean = {
    storeAvailableCheck match {
      case Success(_) => true
      case Failure(x) =>
        //If running on master on main repo build tests MUST be run
        //For non main repo runs like in fork or for PR its fine for test
        //to be cancelled
        if (TestUtils.isBuildingOnMainRepo) throw x else false
    }
  }
}
