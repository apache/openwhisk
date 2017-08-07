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

package whisk.core.database.test

import common.StreamLogging
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.database.ArtifactStoreProvider
import whisk.core.entity.WhiskActivation
import whisk.spi.SpiLoader

@RunWith(classOf[JUnitRunner])
class CouchDBStoreProviderTests extends FlatSpec
        with Matchers
        with WskActorSystem
        with StreamLogging {
    val config = new WhiskConfig(Map(
        dbProvider -> "CouchDB",
        dbProtocol -> "http",
        dbUsername -> "fake",
        dbPassword -> "fake",
        dbHost -> "fake",
        dbPort -> "1234",
        dbActivations -> "activations_fake"))

    behavior of "CouchDBStoreProvider"

    it should "load the same store from config for a specific artifact type" in {
        val artifactStoreProvider = SpiLoader.get[ArtifactStoreProvider]()
        val store1 = artifactStoreProvider.makeStore[WhiskActivation](config, _.dbActivations)
        val store2 = artifactStoreProvider.makeStore[WhiskActivation](config, _.dbActivations)
        store1 shouldBe store2
    }

}
