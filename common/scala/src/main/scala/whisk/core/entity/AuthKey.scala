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

package whisk.core.entity

import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
import spray.json._
import spray.json.DefaultJsonProtocol._

trait AuthKeyEnv {
  val authElements: Map[String, String]
  def toEnvironment = authElements.toJson.asJsObject
  def getCredentials: HttpCredentials
}

/**
 * Authentication key, consisting of a UUID and Secret.
 *
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param k (uuid, key) the uuid and key, assured to be non-null because both types are values
 */
protected[core] case class AuthKey private (authElements: Map[String, String]) extends AuthKeyEnv {
  def uuid: UUID = UUID(this.authElements("api_key").split(":")(0))
  def key: Secret = Secret(this.authElements("api_key").split(":")(1))
  def revoke = AuthKey(uuid, Secret())
  def compact: String = this.authElements("api_key")
  override def toString: String = uuid.toString
  override def getCredentials: HttpCredentials = BasicHttpCredentials(uuid.asString, key.asString)
}

protected[core] object AuthKey {

  /**
   * Creates AuthKey.
   *
   * @param uuid the uuid, assured to be non-null because UUID is a value
   * @param key the key, assured to be non-null because Secret is a value
   */
  protected[core] def apply(uuid: UUID, key: Secret): AuthKey = {
    new AuthKey(Map("api_key" -> s"${uuid.toString}:${key.toString}"))
  }

  /**
   * Creates an auth key for a randomly generated UUID with a randomly generated secret.
   *
   * @return AuthKey
   */
  protected[core] def apply(): AuthKey = AuthKey(UUID(), Secret())

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
  protected[core] def apply(str: String): AuthKey = {
    val (uuid, secret) = str.split(':').toList match {
      case k :: v :: _ => (k, v)
      case k :: Nil    => (k, "")
      case Nil         => ("", "")
    }

    AuthKey(UUID(uuid.trim), Secret(secret.trim))
  }

  protected[core] implicit val serdes: RootJsonFormat[AuthKey] = new RootJsonFormat[AuthKey] {
    def write(k: AuthKey) = k.toEnvironment
    def read(value: JsValue) = new AuthKey(value.convertTo[Map[String, String]])
  }
}
