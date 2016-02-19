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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import whisk.core.entity.SemVer
import whisk.core.dispatcher.Matcher

@RunWith(classOf[JUnitRunner])
class MatcherTests extends FlatSpec {

    behavior of "Matcher"

    it should "accept null arguments but not be valid" in {
        val matcher = new Matcher(null)
        assert(matcher.isValid == false)
    }

    it should "trim and filter out empty paths names" in {
        val matcher = new Matcher("three", " x , y,,z")
        assert(matcher.isValid && matcher.toString == """three: matches on (?i)^/(x)$,(?i)^/(y)$,(?i)^/(z)$""")
    }

    it should "match a simple pattern" in {
        val matcher = new Matcher("name", "^/name$".r)
        assert(matcher.matches("/name").nonEmpty)
        assert(matcher.matches(" /name").nonEmpty)
        assert(matcher.matches("/name ").nonEmpty)
        assert(matcher.matches(" /name ").nonEmpty)
    }

    it should "not match a simple pattern" in {
        val matcher = new Matcher("name", "(?i)^/pattern/name$".r)
        assert(matcher.matches("x/pattern/name").isEmpty)
        assert(matcher.matches("/pattern/namex").isEmpty)
    }

    it should "match a known grouped pattern" in {
        val matcher = new Matcher("name", "(?i)^/pattern/(name)$".r)
        var m = matcher.matches("/pattern/name")
        assert(m.nonEmpty && m(0).group(1) == "name")
    }

    it should "match an unknown grouped pattern" in {
        val matcher = new Matcher("name", "(?i)^/pattern/(.+)$".r)
        var m = matcher.matches("/pattern/name")
        assert(m.nonEmpty && m(0).group(1) == "name")
    }

    it should "make and test patterns that are case sensitive" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths("name", "pattern", false))
        assert(matcher.matches("/pattern/name").nonEmpty)
        assert(matcher.matches(" /pattern/name").nonEmpty)
        assert(matcher.matches("/pattern/name ").nonEmpty)
        assert(matcher.matches("/pattern/NAME").isEmpty)
        assert(matcher.matches("/Pattern/NAME").isEmpty)
    }

    it should "make and test patterns that are not case sensitive" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths("name", "pattern"))
        assert(matcher.matches("/pattern/name").nonEmpty)
        assert(matcher.matches(" /pattern/name").nonEmpty)
        assert(matcher.matches("/pattern/name ").nonEmpty)
        assert(matcher.matches("/pattern/NAME").nonEmpty)
        assert(matcher.matches("/Pattern/NAME").nonEmpty)
    }

    it should "make and test patterns with a prefix that has a leading slash" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths("name", "/pattern"))
        assert(matcher.matches("/pattern/name").nonEmpty)
        assert(matcher.matches(" /pattern/name").nonEmpty)
        assert(matcher.matches("/pattern/name ").nonEmpty)
        assert(matcher.matches("/pattern/NAME").nonEmpty)
        assert(matcher.matches("/Pattern/NAME").nonEmpty)
    }

    it should "make and test patterns with a prefix that has an escaped leading slash" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths("name", "//pattern"))
        assert(matcher.matches("//pattern/name").nonEmpty)
        assert(matcher.matches(" //pattern/name").nonEmpty)
        assert(matcher.matches("//pattern/name ").nonEmpty)
        assert(matcher.matches("//pattern/NAME").nonEmpty)
        assert(matcher.matches("//Pattern/NAME").nonEmpty)
    }

    it should "match grouped patterns with the same prefix" in {
        val matcher = new Matcher("name", "(?i)^/pattern/(foo)$".r, "(?i)^/pattern/(bar)$".r)
        val foo = matcher.matches("/pattern/foo")
        val bar = matcher.matches("/pattern/bar")
        val baz = matcher.matches("/pattern/baz")
        assert(foo.nonEmpty && foo(0).group(1) == "foo")
        assert(bar.nonEmpty && bar(0).group(1) == "bar")
        assert(baz.isEmpty)
    }

    it should "match grouped patterns with different prefix" in {
        val matcher = new Matcher("name", "(?i)^/invoke/(.+)$".r, "(?i)^/trigger/(.+)$".r)
        val foo = matcher.matches("/invoke/foo")
        val bar = matcher.matches("/trigger/bar")
        assert(foo.nonEmpty && foo(0).group(1) == "foo")
        assert(bar.nonEmpty && bar(0).group(1) == "bar")
    }

    it should "match grouped prefix and name" in {
        val matcher = new Matcher("name", "(?i)^/(invoke)/(.+)$".r, "(?i)^/(trigger)/(.+)$".r)
        val foo = matcher.matches("/invoke/foo")
        val bar = matcher.matches("/trigger/bar")
        assert(foo.nonEmpty && foo(0).group(1) == "invoke" && foo(0).group(2) == "foo")
        assert(bar.nonEmpty && bar(0).group(1) == "trigger" && bar(0).group(2) == "bar")
    }

    it should "match multiple grouped names with same prefix" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths("(enable)/(.+),(disable)/(.+)", "/rules"))
        val enable = matcher.matches("/rules/enable/foo")
        val disable = matcher.matches("/rules/disable/bar")
        assert(enable.nonEmpty && enable(0).group(2) == "enable" && enable(0).group(3) == "foo")
        assert(disable.nonEmpty && disable(0).group(2) == "disable" && disable(0).group(3) == "bar")
    }

    it should "match name and sematic version" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths(s"""(.+)/${SemVer.REGEX}""", "/actions/invoke"))
        val info = matcher.matches("/actions/invoke/foobar/0.12.345")
        assert(info.nonEmpty)
        assert(info(0).group(2) == "foobar")
        assert(info(0).group(3) == "0")
        assert(info(0).group(4) == "12")
        assert(info(0).group(5) == "345")
            }

    it should "match name and arbitrary version" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths(s"""(.+)/(.+)""", "/actions/invoke"))
        val info = matcher.matches("/actions/invoke/ns/n/123")
        assert(info(0).group(2) == "ns/n")
        assert(info(0).group(3) == "123")
    }

    it should "match fully qualified name with version" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths(s"""(.+)/(.+)/(.+),(.+)/(.+)""", "/actions/invoke"))
        val info = matcher.matches("/actions/invoke/ns/n/123")
        assert(info(0).group(2) == "ns")
        assert(info(0).group(3) == "n")
        assert(info(0).group(4) == "123")
    }

    it should "match fully qualified name with no version" in {
        val matcher = new Matcher("name", Matcher.makeRegexForPaths(s"""(.+)/(.+)/(.+),(.+)/(.+)""", "/actions/invoke"))
        val info = matcher.matches("/actions/invoke/ns/n")
        assert(info(0).group(2) == "ns")
        assert(info(0).group(3) == "n")
    }
}
