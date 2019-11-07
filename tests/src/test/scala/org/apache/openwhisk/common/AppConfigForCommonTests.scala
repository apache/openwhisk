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
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import common.WhiskProperties
import scala.io.Source

// Tests the logic of definitions in application.conf for common.

trait Helpers {

  def getKeyGenerator = {
    val statsDConfig = Kamon.config.getConfig("kamon.statsd")
    statsDConfig.getString("metric-key-generator")
  }

  def getReporters = {
    val kamonConfig = Kamon.config.getConfig("kamon")
    kamonConfig.getStringList("reporters").toArray
  }

  def configKamon(generator: Option[String], reporter: Option[String]) = {
    // read application.conf for common
    val buffer = Source
      .fromFile(WhiskProperties.getFileRelativeToWhiskHome("common/scala/src/main/resources/application.conf"))
    var content = buffer.getLines.mkString("\n")
    // simulate substitution of environment variables in application.conf
    for (subst <- Seq(("KAMON_STATSD_METRIC_KEY_GENERATOR", generator), ("METRICS_KAMON_REPORTER", reporter))) {
      content = subst match {
        case (name, Some(value)) =>
          // substitute ${name} and ${?name} occurrences with the value
          val pattern = ("\\$\\{\\??" + name + "\\}").r
          pattern.replaceAllIn(content, value)
        case _ => content // no changes
      }
    }
    // reconfigure kamon with the modified application.conf
    val newConfig = ConfigFactory.parseString(content).withFallback(ConfigFactory.load()).resolve()
    Kamon.reconfigure(newConfig)
  }

}

@RunWith(classOf[JUnitRunner])
class MetricsConfigTests extends FlatSpec with BeforeAndAfterAll with Matchers with Helpers {

  // Checks that default settings hold when KAMON_STATSD_METRIC_KEY_GENERATOR and
  // METRICS_KAMON_REPORTER are not defíned
  it should "use defaults for key generator and reporters" in {
    configKamon(None, None)
    getKeyGenerator shouldBe "org.apache.openwhisk.common.WhiskStatsDMetricKeyGenerator"
    getReporters shouldBe Array("kamon.statsd.StatsDReporter")
  }

  // Checks that settings for KAMON_STATSD_METRIC_KEY_GENERATOR and METRICS_KAMON_REPORTER work
  it should "use updated values for key generator and reporters" in {
    val generator = "my.new.generator"
    val reporter = "my.new.reporter"
    configKamon(Some(generator), Some(reporter))
    getKeyGenerator shouldBe generator
    getReporters shouldBe Array(reporter)
  }

}
