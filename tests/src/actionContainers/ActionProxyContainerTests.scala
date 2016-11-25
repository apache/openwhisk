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

import java.io.File
import java.util.Base64

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import ActionContainer.withContainer
import common.TestUtils
import common.WskActorSystem
import spray.json.DefaultJsonProtocol._
import spray.json._

@RunWith(classOf[JUnitRunner])
class ActionProxyContainerTests extends BasicActionRunnerTests with WskActorSystem {

    override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
        withContainer("dockerskeleton", env)(code)
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

        // excluding perl as it not installed in alpine based image
        Seq(("bash", bash), ("python", python))
    }

    /** Standard code samples, should print 'hello' to stdout and echo the input args. */
    val stdEnvSamples = {
        val bash = """
                |#!/bin/bash
                |echo "{ \
                |\"api_host\": \"$__OW_API_HOST\", \"api_key\": \"$__OW_API_KEY\", \
                |\"namespace\": \"$__OW_NAMESPACE\", \"action_name\": \"$__OW_ACTION_NAME\", \
                |\"activation_id\": \"$__OW_ACTIVATION_ID\", \"deadline\": \"$__OW_DEADLINE\" }"
            """.stripMargin.trim

        val python = """
                |#!/usr/bin/env python
                |import os
                |
                |print '{ "api_host": "%s", "api_key": "%s", "namespace": "%s", "action_name" : "%s", "activation_id": "%s", "deadline": "%s" }' % (
                |  os.environ['__OW_API_HOST'], os.environ['__OW_API_KEY'],
                |  os.environ['__OW_NAMESPACE'], os.environ['__OW_ACTION_NAME'],
                |  os.environ['__OW_ACTIVATION_ID'], os.environ['__OW_DEADLINE'])
            """.stripMargin.trim

        val perl = """
                |#!/usr/bin/env perl
                |$a = $ENV{'__OW_API_HOST'};
                |$b = $ENV{'__OW_API_KEY'};
                |$c = $ENV{'__OW_NAMESPACE'};
                |$d = $ENV{'__OW_ACTION_NAME'};
                |$e = $ENV{'__OW_ACTIVATION_ID'};
                |$f = $ENV{'__OW_DEADLINE'};
                |print "{ \"api_host\": \"$a\", \"api_key\": \"$b\", \"namespace\": \"$c\", \"action_name\": \"$d\", \"activation_id\": \"$e\", \"deadline\": \"$f\" }";
            """.stripMargin.trim

        // excluding perl as it not installed in alpine based image
        Seq(("bash", bash), ("python", python))
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

    it should "extract and run a compatible zip exec" in {
        val zip = FileUtils.readFileToByteArray(new File(TestUtils.getTestActionFilename("blackbox.zip")))
        val contents = Base64.getEncoder.encodeToString(zip)

        val (out, err) = withActionContainer() { c =>
            val (initCode, err) = c.init(JsObject("value" -> JsObject("code" -> JsString(contents), "binary" -> JsBoolean(true))))
            initCode should be(200)
            val (runCode, out) = c.run(JsObject())
            runCode should be(200)
            out.get should be(JsObject("msg" -> JsString("hello zip")))
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe "This is an example zip used with the docker skeleton action."
                e shouldBe empty
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
     * @param codeNotReturningJson code to execute, should not return a JSON object
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
    def testEnv(stdEnvSamples: Seq[(String, String)], enforceEmptyOutputStream: Boolean = true, enforceEmptyErrorStream: Boolean = true) = {
        for (s <- stdEnvSamples) {
            it should s"run a ${s._1} script and confirm expected environment variables" in {
                val props = Seq(
                    "api_host" -> "xyz",
                    "api_key" -> "abc",
                    "namespace" -> "zzz",
                    "action_name" -> "xxx",
                    "activation_id" -> "iii",
                    "deadline" -> "123")
                val env = props.map { case (k, v) => s"__OW_${k.toUpperCase()}" -> v }

                val (out, err) = withActionContainer(env.take(1).toMap) { c =>
                    val (initCode, _) = c.init(initPayload(s._2))
                    initCode should be(200)

                    val (runCode, out) = c.run(runPayload(JsObject(), Some(props.toMap.toJson.asJsObject)))
                    runCode should be(200)
                    out shouldBe defined
                    props.map {
                        case (k, v) => withClue(k) {
                            out.get.fields(k) shouldBe JsString(v)
                        }

                    }
                }

                checkStreams(out, err, {
                    case (o, e) =>
                        if (enforceEmptyOutputStream) o shouldBe empty
                        if (enforceEmptyErrorStream) e shouldBe empty
                })
            }
        }
    }
}
