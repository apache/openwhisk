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

package whisk.core.dispatcher.test

trait TestUtils {

    def boundedRetry(seconds: Int, fn: Unit => Boolean): Boolean = {
        val sleepInterval = 1
        var time = 0

        while (time < seconds) {
            if (fn()) return true
            System.out.println(s"Going to sleep for $sleepInterval seconds then will try again")
            Thread.sleep(sleepInterval * 1000)
            time += sleepInterval
        }
        return false
    }

    def logContains(seconds: Int, w: String)(implicit stream: java.io.ByteArrayOutputStream): Boolean = {
        boundedRetry(seconds, Unit => {
            val log = stream.toString()
            log.contains(w)
        })
    }

}