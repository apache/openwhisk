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

package actionContainers

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import ActionContainer.withContainer
import spray.json.JsNull
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsArray

import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class ActionProxyContainerTests extends BasicActionRunnerTests with WskActorSystem {

    override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
        withContainer("openwhisk/dockerskeleton", env)(code)
    }

    val codeNotReturningJson = """
           |#!/bin/sh
           |echo not a json object
        """.stripMargin.trim

    /** Standard code samples, should print 'hello' to stdout and echo the input args. */
    val stdCodeSamples = {
        val bash = """
                |#!/bin/bash
                |echo 'hello stdout'
                |echo 'hello stderr' 1>&2
                |if [[ -z $1 || $1 == '{}' ]]; then
                |   echo '{ "msg": "Hello from bash script!" }'
                |else
                |   echo $1 # echo the arguments back as the result
                |fi
            """.stripMargin.trim

        val python = """
                |#!/usr/bin/env python
                |import sys
                |print 'hello stdout'
                |print >> sys.stderr, 'hello stderr'
                |print(sys.argv[1])
            """.stripMargin.trim

        val perl = """
                |#!/usr/bin/env perl
                |print STDOUT "hello stdout\n";
                |print STDERR "hello stderr\n";
                |print $ARGV[0];
            """.stripMargin.trim

        Seq(("bash", bash), ("python", python), ("perl", perl))
    }

    /** Standard code samples, should print 'hello' to stdout and echo the input args. */
    val stdEnvSamples = {
        val bash = """
                |#!/bin/bash
                |echo "{ \"auth\": \"$AUTH_KEY\", \"edge\": \"$EDGE_HOST\" }"
            """.stripMargin.trim

        val python = """
                |#!/usr/bin/env python
                |import os
                |print '{ "auth": "%s", "edge": "%s" }' % (os.environ['AUTH_KEY'], os.environ['EDGE_HOST'])
            """.stripMargin.trim

        val perl = """
                |#!/usr/bin/env perl
                |$a = $ENV{'AUTH_KEY'};
                |$e = $ENV{'EDGE_HOST'};
                |print "{ \"auth\": \"$a\", \"edge\": \"$e\" }";
            """.stripMargin.trim

        Seq(("bash", bash), ("python", python), ("perl", perl))
    }

    behavior of "openwhisk/dockerskeleton"

    it should "run sample without init" in {
        val (out, err) = withActionContainer() { c =>
            val (runCode, out) = c.run(JsObject())
            runCode should be(200)
            out should be(Some(JsObject("error" -> JsString("This is a stub action. Replace it with custom logic."))))
        }

        checkStreams(out, err, {
            case (o, _) => o should include("This is a stub action")
        })
    }

    it should "run sample with init that does nothing" in {
        val (out, err) = withActionContainer() { c =>
            val (initCode, _) = c.init(JsObject())
            initCode should be(200)
            val (runCode, out) = c.run(JsObject())
            runCode should be(200)
            out should be(Some(JsObject("error" -> JsString("This is a stub action. Replace it with custom logic."))))
        }

        checkStreams(out, err, {
            case (o, _) => o should include("This is a stub action")
        })
    }

    it should "respond with 404 for bad run argument" in {
        val (out, err) = withActionContainer() { c =>
            val (runCode, out) = c.run(runPayload(JsString("A")))
            runCode should be(404)
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "fail to run a bad script" in {
        val (out, err) = withActionContainer() { c =>
            val (initCode, _) = c.init(initPayload(""))
            initCode should be(200)
            val (runCode, out) = c.run(JsNull)
            runCode should be(502)
            out should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
        }

        checkStreams(out, err, {
            case (o, _) => o should include("error")
        })
    }

    testNotReturningJson(codeNotReturningJson, checkResultInLogs = true)
    testEcho(stdCodeSamples)
    testEnv(stdEnvSamples)
}

trait BasicActionRunnerTests extends ActionProxyContainerTestUtils {
    def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit): (String, String)

    /**
     * Runs tests for actions which do not return a dictionary and confirms expected error messages.
     * @param codeNotReturningJson code to exectue, should not return a JSON object
     * @param checkResultInLogs should be true iff the result of the action is expected to appear in stdout or stderr
     */
    def testNotReturningJson(codeNotReturningJson: String, checkResultInLogs: Boolean = true) = {
        it should "run and report an error for script not returning a json object" in {
            val (out, err) = withActionContainer() { c =>
                val (initCode, _) = c.init(initPayload(codeNotReturningJson))
                initCode should be(200)
                val (runCode, out) = c.run(JsObject())
                runCode should be(502)
                out should be(Some(JsObject("error" -> JsString("The action did not return a dictionary."))))
            }

            checkStreams(out, err, {
                case (o, e) =>
                    if (checkResultInLogs) {
                        (o + e) should include("not a json object")
                    } else {
                        o shouldBe empty
                        e shouldBe empty
                    }
            })
        }
    }

    /**
     * Runs tests for code samples which are expected to echo the input arguments
     * and print hello [stdout, stderr].
     */
    def testEcho(stdCodeSamples: Seq[(String, String)]) = {
        for (s <- stdCodeSamples) {
            it should s"run a ${s._1} script" in {
                val argss = List(
                    JsObject("string" -> JsString("hello")),
                    JsObject("numbers" -> JsArray(JsNumber(42), JsNumber(1))),
                    // JsObject("boolean" -> JsBoolean(true)), // fails with swift3 returning boolean: 1
                    JsObject("object" -> JsObject("a" -> JsString("A"))))

                val (out, err) = withActionContainer() { c =>
                    val (initCode, _) = c.init(initPayload(s._2))
                    initCode should be(200)

                    for (args <- argss) {
                        val (runCode, out) = c.run(runPayload(args))
                        runCode should be(200)
                        out should be(Some(args))
                    }
                }

                checkStreams(out, err, {
                    case (o, e) =>
                        o should include("hello stdout")
                        e should include("hello stderr")
                }, argss.length)
            }
        }
    }

    /** Runs tests for code samples which are expected to return the expected standard environment {auth, edge}. */
    def testEnv(stdEnvSamples: Seq[(String, String)], enforceEmptyOutputStream: Boolean = true) = {
        for (s <- stdEnvSamples) {
            it should s"run a ${s._1} script and confirm expected environment variables" in {
                val auth = JsString("abc")
                val edge = "xyz"
                val env = Map("EDGE_HOST" -> edge)

                val (out, err) = withActionContainer(env) { c =>
                    val (initCode, _) = c.init(initPayload(s._2))
                    initCode should be(200)

                    val (runCode, out) = c.run(runPayload(JsObject(), Some(JsObject("authKey" -> auth))))
                    runCode should be(200)
                    out shouldBe defined
                    out.get.fields("auth") shouldBe auth
                    out.get.fields("edge") shouldBe JsString(edge)
                }

                checkStreams(out, err, {
                    case (o, e) =>
                        if (enforceEmptyOutputStream) o shouldBe empty
                        e shouldBe empty
                })
            }
        }
    }
}
