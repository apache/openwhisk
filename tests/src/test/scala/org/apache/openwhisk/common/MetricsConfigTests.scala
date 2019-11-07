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

package org.apache.openwhisk.common
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import common.WhiskProperties

trait Helpers {
  def configKamon() = {
    val configFile = WhiskProperties.getFileRelativeToWhiskHome("common/scala/src/main/resources/application.conf")
    val newConfig = ConfigFactory.parseFile(configFile).withFallback(ConfigFactory.load()).resolve()
    Kamon.reconfigure(newConfig)
  }
  def getKeyGenerator() = {
    val statsDConfig = Kamon.config.getConfig("kamon.statsd")
    statsDConfig.getString("metric-key-generator")
  }
  def getReporters() = {
    val kamonConfig = Kamon.config.getConfig("kamon")
    kamonConfig.getStringList("reporters").toArray
  }
}

@RunWith(classOf[JUnitRunner])
class MetricsConfigTests extends FlatSpec with Matchers with Helpers {
  // checks for default values
  it should "use default values for key generator and reporters" in {
    configKamon()
    // should be the standard generator and reporter
    getKeyGenerator() shouldBe "org.apache.openwhisk.common.WhiskStatsDMetricKeyGenerator"
    getReporters() shouldBe Array("kamon.statsd.StatsDReporter")
  }
}

@RunWith(classOf[JUnitRunner])
class MetricsConfigChangeReporterAndKeyGeneratorTests extends FlatSpec with Matchers with Helpers {
  // another junit runner instance to handle environment variables correctly,
  // setting/unsetting system environment vars only works once for kamon (re)configuration
  it should "use values from environment variables for key generator and reporters" in {
    System.setProperty("KAMON_STATSD_METRIC_KEY_GENERATOR", "my.new.generator")
    System.setProperty("METRICS_KAMON_REPORTER", "my.new.reporter")
    configKamon()
    getKeyGenerator() shouldBe "my.new.generator"
    getReporters() shouldBe Array("my.new.reporter")
  }
}
