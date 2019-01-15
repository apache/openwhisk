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

package org.apache.openwhisk.core.aws
import com.typesafe.config.Config
import software.amazon.awssdk.auth.credentials.{
  AnonymousCredentialsProvider,
  AwsBasicCredentials,
  AwsCredentialsProvider,
  AwsSessionCredentials,
  DefaultCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.{AwsRegionProvider, DefaultAwsRegionProviderChain}

object CredentialProvider {

  def apply(config: Config): AwsCredentialsProvider = {
    val credProviderPath = "aws.credentials.provider"
    if (config.hasPath(credProviderPath)) {
      config.getString(credProviderPath) match {
        case "default" =>
          defaultProvider()

        case "static" =>
          //TODO Use pureconfig
          val aki = config.getString("aws.credentials.access-key-id")
          val sak = config.getString("aws.credentials.secret-access-key")
          val tokenPath = "aws.credentials.token"
          val creds = if (config.hasPath(tokenPath)) {
            AwsSessionCredentials.create(aki, sak, config.getString(tokenPath))
          } else {
            AwsBasicCredentials.create(aki, sak)
          }
          StaticCredentialsProvider.create(creds)

        case "anon" =>
          AnonymousCredentialsProvider.create()

        case _ ⇒
          defaultProvider()
      }
    } else {
      defaultProvider()
    }
  }

  private def defaultProvider() =
    DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build()
}

object RegionProvider {

  def apply(config: Config): AwsRegionProvider = {
    val regionProviderPath = "aws.region.provider"

    val staticRegionProvider = new AwsRegionProvider {
      lazy val getRegion: Region = Region.of(config.getString("aws.region.default-region"))
    }

    if (config.hasPath(regionProviderPath)) {
      config.getString(regionProviderPath) match {
        case "static" =>
          staticRegionProvider
        case _ =>
          new DefaultAwsRegionProviderChain()
      }
    } else {
      new DefaultAwsRegionProviderChain()
    }
  }
}
