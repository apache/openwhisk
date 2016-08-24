/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package packages.slack

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.TestHelpers
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class SlackTests extends TestHelpers
    with WskTestHelpers
    with BeforeAndAfterAll
    with JsHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()
    val username = "Test";
    val channel = "gittoslack";
    val text = "Hello Test!";
    val url = "https://hooks.slack.com/services/ABC/";
    val slackAction = "/whisk.system/slack/post"

    val expectedChannel = "channel: '" + channel + "'"
    val expectedUsername = "username: '" + username + "'";
    val expectedText = "text: '" + text + "'";

    "Slack Package" should "print the object being sent to slack" in {
        val run = wsk.action.invoke(slackAction, Map("username" -> username.toJson, "channel" -> channel.toJson, "text" -> text.toJson, "url" -> url.toJson))
        withActivation(wsk.activation, run) {
            activation =>
                activation.response.success shouldBe true
                val logs = activation.logs.get.mkString("\n")
                logs should include(expectedChannel)
                logs should include(expectedUsername)
                logs should include(expectedText)
                logs should include(url)
        }
    }

}
