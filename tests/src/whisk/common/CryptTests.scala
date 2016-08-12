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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CryptTests extends FlatSpec with Matchers {

    "Crypt" should "produce the original string after encoding and decoding it" in {
        val cr = new Crypt("0123456789ABCFEF")
        val pt = "a brown fox jumped over the fence"
        val ct = cr.encrypt(pt)
        val dt = cr.decrypt(ct)
        assert(dt === pt);
    }
}
