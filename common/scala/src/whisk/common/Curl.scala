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

package whisk.common

/**
 * Utility methods for spawning curl commands.
 */
object Curl extends Logging {

    private val curl = "/usr/bin/curl"

    def put(url: String, body: String) = {
        SimpleExec.syncRunCmd(Array(curl, "-sS", "-XPUT", url, "-d", body))(TransactionId.unknown)
    }

    def get(url: String) = {
        SimpleExec.syncRunCmd(Array(curl, "-sS", "-XGET", url))(TransactionId.unknown)
    }
}
