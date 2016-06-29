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

package whisk.core.entitlement

/** A privilege is a right that is granted to a subject for a given resources. */
protected[core] case class Privilege private (val right: String) extends AnyVal {
    override def toString = right
}

/** An enumeration of privileges available to subjects. */
protected[core] object Privilege {
    val READ = new Privilege("read")
    val PUT = new Privilege("put") // create or update
    val DELETE = new Privilege("delete")
    val ACTIVATE = new Privilege("activate") // usually a POST
    val REJECT = new Privilege("reject")
}
