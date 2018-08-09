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
// Copyright 2017-2018 Adobe.
package com.karate.openwhisk.performance

import com.karate.openwhisk.wskadmin._
import com.intuit.karate.gatling.PreDef._
import io.gatling.core.Predef._
import com.intuit.karate.FileUtils
import java.io.File
import java.nio.file.{Files, Paths}


import com.intuit.karate.FileUtils
import com.karate.openwhisk.utils.OWFileUtil
//import io.gatling.http.Predef._

import scala.concurrent.duration._

class LoadTestOnCreateAction extends Simulation {
  before{
    println("Simulation is about to start!")
    val ar = new WskAdminRunner()
    ar.WskAdminRunner()
  }
  val createActionTest = scenario("create").exec(karateFeature("classpath:com/karate/openwhisk/performance/load-test-create-action.feature"))


  setUp(createActionTest.inject(rampUsers(5) over (5 seconds))
    ).maxDuration(1 minutes).assertions(global.responseTime.mean.lt(1100))

  after {
    println("Deleting authFile.txt")
    val path : File = FileUtils.getDirContaining(classOf[OWFileUtil])
    println(Files.deleteIfExists(Paths.get(path+"/authFile.txt")))
    println("Simulation is finished!")
  }
}
