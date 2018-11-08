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

package org.apache.openwhisk.core.entity

import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * Authentication key for Basic Authentication, consisting of a UUID and Secret.
 *
 * @param uuid the uuid assured to be non-null because both types are values
 * @param key the key assured to be non-null because both types are values
 */
protected[core] case class BasicAuthenticationAuthKey(uuid: UUID, key: Secret)
    extends GenericAuthKey(JsObject("api_key" -> s"$uuid:$key".toJson)) {
  def revoke = new BasicAuthenticationAuthKey(uuid, Secret())
  def compact: String = s"$uuid:$key"
  override def toString: String = uuid.toString
  override def getCredentials: Option[HttpCredentials] = Some(BasicHttpCredentials(uuid.asString, key.asString))
}

protected[core] object BasicAuthenticationAuthKey {

  /**
   * Creates AuthKey from a string where the uuid and key are separated by a colon.
   * If the string contains more than one colon, all values are ignored except for
   * the first two hence "k:v*" produces ("k","v").
   *
   * @param str the string containing uuid and key separated by colon
   * @return AuthKey if argument is properly formatted
   * @throws IllegalArgumentException if argument is not well formed
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(str: String): BasicAuthenticationAuthKey = {
    val (uuid, secret) = str.split(':').toList match {
      case k :: v :: _ => (k, v)
      case k :: Nil    => (k, "")
      case Nil         => ("", "")
    }

    new BasicAuthenticationAuthKey(UUID(uuid.trim), Secret(secret.trim))
  }

  /**
   * Creates an auth key for a randomly generated UUID with a randomly generated secret.
   */
  protected[core] def apply(): BasicAuthenticationAuthKey = new BasicAuthenticationAuthKey(UUID(), Secret())

}
