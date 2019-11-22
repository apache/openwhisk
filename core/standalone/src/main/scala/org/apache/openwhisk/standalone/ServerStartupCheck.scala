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

package org.apache.openwhisk.standalone

import java.net.{HttpURLConnection, URL}

import akka.http.scaladsl.model.Uri
import com.google.common.base.Stopwatch
import org.apache.openwhisk.utils.retry

import scala.concurrent.duration._

class ServerStartupCheck(uri: Uri, serverName: String) {

  def waitForServerToStart(): Unit = {
    val w = Stopwatch.createStarted()
    retry({
      println(s"Waiting for $serverName server at $uri to start since $w")
      require(getResponseCode() == 200)
    }, 30, Some(1.second))
  }

  private def getResponseCode(): Int = {
    val u = new URL(uri.toString())
    val hc = u.openConnection().asInstanceOf[HttpURLConnection]
    hc.setRequestMethod("GET")
    hc.connect()
    hc.getResponseCode
  }
}
