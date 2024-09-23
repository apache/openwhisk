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

/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package org.apache.openwhisk.core.database.s3

import scala.util.Try
import scala.xml.{Elem, XML}

/**
 * Exception thrown by S3 operations.
 *
 * Copied from https://github.com/akka/alpakka/blob/v1.0.2/s3/src/main/scala/akka/stream/alpakka/s3/S3Exception.scala
 */
private[s3] class S3Exception(val code: String, val message: String, val requestId: String, val hostId: String)
    extends RuntimeException(message) {

  def this(xmlResponse: Elem) =
    this(
      (xmlResponse \ "Code").text,
      (xmlResponse \ "Message").text,
      (xmlResponse \ "RequestID").text,
      (xmlResponse \ "HostID").text)

  def this(response: String) =
    this(
      Try(XML.loadString(response)).getOrElse(
        <Error><Code>-</Code><Message>{response}</Message><RequestID>-</RequestID><HostID>-</HostID></Error>))

  override def toString: String =
    s"${super.toString} (Code: $code, RequestID: $requestId, HostID: $hostId)"

}
