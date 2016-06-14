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

package packages;

import static common.Pair.make;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.code.tempusfugit.concurrency.ParallelRunner;

import common.TestUtils;
import common.WskCli;

/**
 * Tests for rules using command line interface
 */
@RunWith(ParallelRunner.class)
public class SlackTests {
  private static final Boolean usePythonCLI = false;
  private static final WskCli wsk = new WskCli(usePythonCLI);

  private static final int DEFAULT_WAIT = 100;

  @Test(timeout=120*1000)
  public void createSlackMessageObject() throws Exception {
    String username = "Test";
    String channel = "gittoslack";
    String text = "Hello Test!";
    String url = "https://hooks.slack.com/services/ABC/";

    String activationId = wsk.invoke("/whisk.system/slack/post", TestUtils.makeParameter(
            make("username", username),
            make("channel", channel),
            make("text", text), make("url", url)));

    String expectedChannel = "channel: '" + channel + "'";
    String expectedUsername = "username: '" + username + "'";
    String expectedText = "text: '" + text + "'";
    String expectedIcon = "icon_emoji: undefined";

    assertTrue("Expected message not found: " + expectedChannel, wsk.logsForActivationContain(activationId, expectedChannel, DEFAULT_WAIT));
    assertTrue("Expected message not found: " + expectedUsername, wsk.logsForActivationContain(activationId, expectedUsername, DEFAULT_WAIT));
    assertTrue("Expected message not found: " + expectedText, wsk.logsForActivationContain(activationId, expectedText, DEFAULT_WAIT));
    assertTrue("Expected message not found: " + expectedIcon, wsk.logsForActivationContain(activationId, expectedIcon, DEFAULT_WAIT));
    assertTrue("Expected message not found: " + url, wsk.logsForActivationContain(activationId, url, DEFAULT_WAIT));
  }

}
