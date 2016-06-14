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

import scala.util.matching.Regex
import scala.util.matching.Regex.Match

/**
 * Creates a match rule to match a kafka topic against one or more regular expressions.
 *
 * @param n the name for this rule -- no semantic effect,just used for debugging and printing
 * @param p one or more regular expression to match a topic against
 */
class Matcher(n: String, p: Regex*) {

    def this(n: String, p: List[Regex]) =
        this(n, p: _*)

    def this(n: String, p: String) =
        this(n, Matcher.makeRegexForPaths(p): _*)

    /**
     * Checks if a topic matches any of the registered path patterns.
     *
     * @param topic the string to check patterns against.
     * @return sequence of Regex.Match which can be queried for grouped matches
     *         if there are no matches, the sequence is empty
     */
    def matches(topic: String): Seq[Match] = {
        val t = if (topic != null) topic.trim else ""
        if (t.nonEmpty) {
            paths.flatMap { p =>
                for (m <- p findAllMatchIn t) yield m
            }
        } else Seq[Match]()
    }

    val name = if (n != null) n.trim else ""
    def isValid = name.nonEmpty && paths.length > 0
    override def hashCode = name.hashCode
    override def toString = s"$name: matches on " + paths.mkString(",")
    private val paths = if (p != null) p.filter { x => x != null } else List[Regex]()
}

object Matcher {

    /**
     * Makes regex array for given comma separated paths string, each regex is
     * of the form "(?i)^[/prefix]/(name)$".
     *
     * @param names required string of comma separated path names
     * @param prefix optional prefix to the path, may be null or empty
     * @param ignoreCase optional, true to ignore case otherwise case-sensitive
     */
    def makeRegexForPaths(names: String, prefix: String = null, ignoreCase: Boolean = true): List[Regex] =
        if (names != null) {
            names.split(',')
                .filter { x => x != null && x.trim.nonEmpty }
                .toList
                .map(s => makeRegexForPath(s, prefix, ignoreCase))
        } else List[Regex]()

    /**
     * Makes regex string of the form "(?i)^[/prefix]/(name)$".
     *
     * @param name required name
     * @param prefix optional prefix to the name, may be null or empty
     * @param ignoreCase optional, true to ignore case otherwise case-sensitive
     */
    def makeRegexForPath(name: String, prefix: String = null, ignoreCase: Boolean = true): Regex = {
        val trimPrefix = if (prefix != null) prefix.trim.stripMargin('/') else ""
        val trimName = if (name != null) name.trim.stripMargin('/') else ""
        val caseString = if (ignoreCase) "(?i)" else ""
        val sep = if (trimPrefix.isEmpty) "" else "/"
        if (trimName.nonEmpty) {
            s"$caseString^$sep$trimPrefix/($trimName)$$".r
        } else null
    }
}
