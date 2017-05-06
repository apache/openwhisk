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

package common

import scala.collection.mutable

/**
 * Extensions for Functions to provide introspection of their respective calls.
 *
 * Use like:
 *     val func = LoggedFunction { (a, b) => a + b }
 *     func(1, 2)
 *     func.calls should have size 1
 *     func.calls.head shouldBe (1, 2)
 */

class LoggedFunction2[A1, A2, B](body: (A1, A2) => B) extends Function2[A1, A2, B] {
    val calls = mutable.Buffer[(A1, A2)]()

    override def apply(v1: A1, v2: A2): B = {
        calls += ((v1, v2))
        body(v1, v2)
    }
}

class LoggedFunction5[A1, A2, A3, A4, A5, B](body: (A1, A2, A3, A4, A5) => B) extends Function5[A1, A2, A3, A4, A5, B] {
    val calls = mutable.Buffer[(A1, A2, A3, A4, A5)]()

    override def apply(v1: A1, v2: A2, v3: A3, v4: A4, v5: A5): B = {
        calls += ((v1, v2, v3, v4, v5))
        body(v1, v2, v3, v4, v5)
    }
}

object LoggedFunction {
    def apply[A1, A2, B](body: (A1, A2) => B) = new LoggedFunction2(body)
    def apply[A1, A2, A3, A4, A5, B](body: (A1, A2, A3, A4, A5) => B) = new LoggedFunction5(body)
}
