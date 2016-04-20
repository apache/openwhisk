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

package whisk.core.dispatcher

import scala.concurrent.Future
import scala.util.matching.Regex.Match
import spray.json.JsObject
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.dispatcher.Matcher.makeRegexForPaths
import spray.json.JsObject
import whisk.core.connector.{ ActivationMessage => Message }

/**
 * Abstract base class for a handler for a kafka message.
 *
 * This extends Matcher, which provides dispatch logic functionality based on kafka topics.
 */
abstract class DispatchRule(
    ruleName: String,
    topicPrefix: String,
    topicPatterns: String)
    extends Matcher(ruleName, makeRegexForPaths(topicPatterns, topicPrefix))
    with Logging {

    /**
     * Invokes a handler for a Kafka messages. This method should only be called if matches is not empty.
     * The method is run inside a future. If the method fails with an exception, the exception completes
     * the wrapping future within which the method is run.
     *
     * @param topic the topic the message came in on
     * @param msg the Message object to process
     * @param matches a sequences of pattern matches that triggered this action
     * @param transid the transaction id for the kafka message
     * @return Future that execute the handler
     */
    def doit(topic: String, msg: Message, matches: Seq[Match])(implicit transid: TransactionId): Future[Any]
}
