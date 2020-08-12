/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package common

import java.io.File
import java.time.Instant

import scala.Left
import scala.Right
import scala.collection.mutable.Buffer
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import common.TestUtils._
import spray.json.JsObject
import spray.json.JsValue
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.utils.retry

import FullyQualifiedNames.fqn
import FullyQualifiedNames.resolve

/**
 * Provide Scala bindings for the whisk CLI.
 *
 * Each of the top level CLI commands is a "noun" class that extends one
 * of several traits that are common to the whisk collections and corresponds
 * to one of the top level CLI nouns.
 *
 * Each of the "noun" classes mixes in the RunCliCmd trait which runs arbitrary
 * wsk commands and returns the results. Optionally RunCliCmd can validate the exit
 * code matched a desired value.
 *
 * The various collections support one or more of these as common traits:
 * list, get, delete, and sanitize.
 *
 * Sanitize is akin to delete but accepts a failure because entity may not
 * exit. Additionally, some of the nouns define custom commands.
 *
 * All of the commands define default values that are either optional
 * or omitted in the common case. This makes for a compact implementation
 * instead of using a Builder pattern.
 *
 * An implicit WskProps instance is required for all of CLI commands. This
 * type provides the authentication key for the API as well as the namespace.
 * It also sets the apihost and apiversion explicitly to avoid ambiguity with
 * a local property file if it exists.
 */
class Wsk(cliPath: String = Wsk.defaultCliPath) extends WskOperations with RunCliCmd {

  assert({
    val f = new File(cliPath)
    f.exists && f.isFile && f.canExecute
  }, s"did not find $cliPath")

  override def baseCommand = Buffer(cliPath)

  override implicit val action = new CliActionOperations(this)
  override implicit val trigger = new CliTriggerOperations(this)
  override implicit val rule = new CliRuleOperations(this)
  override implicit val activation = new CliActivationOperations(this)
  override implicit val pkg = new CliPackageOperations(this)
  override implicit val namespace = new CliNamespaceOperations(this)
  override implicit val api = new CliGatewayOperations(this)
}

trait CliListOrGetFromCollectionOperations extends ListOrGetFromCollectionOperations {
  val wsk: RunCliCmd

  /**
   * List entities in collection.
   *
   * @param namespace (optional) if specified must be  fully qualified namespace
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def list(namespace: Option[String] = None,
                    limit: Option[Int] = None,
                    nameSort: Option[Boolean] = None,
                    expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "list", resolve(namespace), "--auth", wp.authKey) ++ {
      limit map { l =>
        Seq("--limit", l.toString)
      } getOrElse Seq.empty
    } ++ {
      nameSort map { n =>
        Seq("--name-sort")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Gets entity from collection.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def get(name: String,
                   expectedExitCode: Int = SUCCESS_EXIT,
                   summary: Boolean = false,
                   fieldFilter: Option[String] = None,
                   url: Option[Boolean] = None,
                   save: Option[Boolean] = None,
                   saveAs: Option[String] = None)(implicit wp: WskProps): RunResult = {

    val params = Seq(noun, "get", "--auth", wp.authKey) ++
      Seq(fqn(name)) ++ { if (summary) Seq("--summary") else Seq.empty } ++ {
      fieldFilter map { f =>
        Seq(f)
      } getOrElse Seq.empty
    } ++ {
      url map { u =>
        Seq("--url")
      } getOrElse Seq.empty
    } ++ {
      save map { s =>
        Seq("--save")
      } getOrElse Seq.empty
    } ++ {
      saveAs map { s =>
        Seq("--save-as", s)
      } getOrElse Seq.empty
    }

    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }
}

trait CliDeleteFromCollectionOperations extends DeleteFromCollectionOperations {
  val wsk: RunCliCmd

  /**
   * Deletes entity from collection.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def delete(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    wsk.cli(wp.overrides ++ Seq(noun, "delete", "--auth", wp.authKey, fqn(name)), expectedExitCode)
  }

  /**
   * Deletes entity from collection but does not assert that the command succeeds.
   * Use this if deleting an entity that may not exist and it is OK if it does not.
   *
   * @param name either a fully qualified name or a simple entity name
   */
  override def sanitize(name: String)(implicit wp: WskProps): RunResult = {
    delete(name, DONTCARE_EXIT)
  }
}

class CliActionOperations(override val wsk: RunCliCmd)
    extends CliListOrGetFromCollectionOperations
    with CliDeleteFromCollectionOperations
    with HasActivation
    with ActionOperations {

  override protected val noun = "action"

  /**
   * Creates action. Parameters mirror those available in the CLI.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(
    name: String,
    artifact: Option[String],
    kind: Option[String] = None, // one of docker, copy, sequence or none for autoselect else an explicit type
    main: Option[String] = None,
    docker: Option[String] = None,
    parameters: Map[String, JsValue] = Map.empty,
    annotations: Map[String, JsValue] = Map.empty,
    delAnnotations: Array[String] = Array(),
    parameterFile: Option[String] = None,
    annotationFile: Option[String] = None,
    timeout: Option[Duration] = None,
    memory: Option[ByteSize] = None,
    logsize: Option[ByteSize] = None,
    concurrency: Option[Int] = None,
    shared: Option[Boolean] = None,
    update: Boolean = false,
    web: Option[String] = None,
    websecure: Option[String] = None,
    expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name)) ++ {
      artifact map { Seq(_) } getOrElse Seq.empty
    } ++ {
      kind map { k =>
        if (k == "sequence" || k == "copy" || k == "native") Seq(s"--$k")
        else Seq("--kind", k)
      } getOrElse Seq.empty
    } ++ {
      main.toSeq flatMap { p =>
        Seq("--main", p)
      }
    } ++ {
      docker.toSeq flatMap { p =>
        Seq("--docker", p)
      }
    } ++ {
      parameters flatMap { p =>
        Seq("-p", p._1, p._2.compactPrint)
      }
    } ++ {
      annotations flatMap { p =>
        Seq("-a", p._1, p._2.compactPrint)
      }
    } ++ {
      delAnnotations flatMap { p =>
        Seq("--del-annotation", p)
      }
    } ++ {
      parameterFile map { pf =>
        Seq("-P", pf)
      } getOrElse Seq.empty
    } ++ {
      annotationFile map { af =>
        Seq("-A", af)
      } getOrElse Seq.empty
    } ++ {
      timeout map { t =>
        Seq("-t", t.toMillis.toString)
      } getOrElse Seq.empty
    } ++ {
      memory map { m =>
        Seq("-m", m.toMB.toString)
      } getOrElse Seq.empty
    } ++ {
      logsize map { l =>
        Seq("-l", l.toMB.toString)
      } getOrElse Seq.empty
    } ++ {
      concurrency map { c =>
        Seq("-c", c.toString)
      } getOrElse Seq.empty
    } ++ {
      shared map { s =>
        Seq("--shared", if (s) "yes" else "no")
      } getOrElse Seq.empty
    } ++ {
      web map { w =>
        Seq("--web", w)
      } getOrElse Seq.empty
    } ++ {
      websecure map { ws =>
        Seq("--web-secure", ws)
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Invokes action. Parameters mirror those available in the CLI.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def invoke(name: String,
                      parameters: Map[String, JsValue] = Map.empty,
                      parameterFile: Option[String] = None,
                      blocking: Boolean = false,
                      result: Boolean = false,
                      expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "invoke", "--auth", wp.authKey, fqn(name)) ++ {
      parameters flatMap { p =>
        Seq("-p", p._1, p._2.compactPrint)
      }
    } ++ {
      parameterFile map { pf =>
        Seq("-P", pf)
      } getOrElse Seq.empty
    } ++ { if (blocking) Seq("--blocking") else Seq.empty } ++ { if (result) Seq("--result") else Seq.empty }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }
}

class CliTriggerOperations(override val wsk: RunCliCmd)
    extends CliListOrGetFromCollectionOperations
    with CliDeleteFromCollectionOperations
    with HasActivation
    with TriggerOperations {

  override protected val noun = "trigger"

  /**
   * Creates trigger. Parameters mirror those available in the CLI.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      parameters: Map[String, JsValue] = Map.empty,
                      annotations: Map[String, JsValue] = Map.empty,
                      parameterFile: Option[String] = None,
                      annotationFile: Option[String] = None,
                      feed: Option[String] = None,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name)) ++ {
      feed map { f =>
        Seq("--feed", fqn(f))
      } getOrElse Seq.empty
    } ++ {
      parameters flatMap { p =>
        Seq("-p", p._1, p._2.compactPrint)
      }
    } ++ {
      annotations flatMap { p =>
        Seq("-a", p._1, p._2.compactPrint)
      }
    } ++ {
      parameterFile map { pf =>
        Seq("-P", pf)
      } getOrElse Seq.empty
    } ++ {
      annotationFile map { af =>
        Seq("-A", af)
      } getOrElse Seq.empty
    } ++ {
      shared map { s =>
        Seq("--shared", if (s) "yes" else "no")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Fires trigger. Parameters mirror those available in the CLI.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def fire(name: String,
                    parameters: Map[String, JsValue] = Map.empty,
                    parameterFile: Option[String] = None,
                    expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "fire", "--auth", wp.authKey, fqn(name)) ++ {
      parameters flatMap { p =>
        Seq("-p", p._1, p._2.compactPrint)
      }
    } ++ {
      parameterFile map { pf =>
        Seq("-P", pf)
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }
}

class CliRuleOperations(override val wsk: RunCliCmd)
    extends CliListOrGetFromCollectionOperations
    with CliDeleteFromCollectionOperations
    with WaitFor
    with RuleOperations {

  override protected val noun = "rule"

  /**
   * Creates rule. Parameters mirror those available in the CLI.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param trigger must be a simple name
   * @param action must be a simple name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      trigger: String,
                      action: String,
                      annotations: Map[String, JsValue] = Map.empty,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name), (trigger), (action)) ++ {
      annotations flatMap { p =>
        Seq("-a", p._1, p._2.compactPrint)
      }
    } ++ {
      shared map { s =>
        Seq("--shared", if (s) "yes" else "no")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Deletes rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def delete(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    super.delete(name, expectedExitCode)
  }

  /**
   * Enables rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def enable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    wsk.cli(wp.overrides ++ Seq(noun, "enable", "--auth", wp.authKey, fqn(name)), expectedExitCode)
  }

  /**
   * Disables rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def disable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    wsk.cli(wp.overrides ++ Seq(noun, "disable", "--auth", wp.authKey, fqn(name)), expectedExitCode)
  }

  /**
   * Checks state of rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def state(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    wsk.cli(wp.overrides ++ Seq(noun, "status", "--auth", wp.authKey, fqn(name)), expectedExitCode)
  }
}

class CliActivationOperations(val wsk: RunCliCmd) extends ActivationOperations with HasActivation with WaitFor {

  protected val noun = "activation"

  /**
   * Activation polling console.
   *
   * @param duration exits console after duration
   * @param since (optional) time travels back to activation since given duration
   * @param actionName (optional) name of entity to filter activation records on.
   */
  override def console(duration: Duration,
                       since: Option[Duration] = None,
                       expectedExitCode: Int = SUCCESS_EXIT,
                       actionName: Option[String] = None)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "poll") ++ {
      actionName map { name =>
        Seq(name)
      } getOrElse Seq.empty
    } ++ Seq("--auth", wp.authKey, "--exit", duration.toSeconds.toString) ++ {
      since map { s =>
        Seq("--since-seconds", s.toSeconds.toString)
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Lists activations.
   *
   * @param filter (optional) if define, must be a simple entity name
   * @param limit (optional) the maximum number of activation to return
   * @param since (optional) only the activations since this timestamp are included
   * @param skip (optional) the number of activations to skip
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def list(filter: Option[String] = None,
           limit: Option[Int] = None,
           since: Option[Instant] = None,
           skip: Option[Int] = None,
           expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "list", "--auth", wp.authKey) ++ { filter map { Seq(_) } getOrElse Seq.empty } ++ {
      limit map { l =>
        Seq("--limit", l.toString)
      } getOrElse Seq.empty
    } ++ {
      since map { i =>
        Seq("--since", i.toEpochMilli.toString)
      } getOrElse Seq.empty
    } ++ {
      skip map { i =>
        Seq("--skip", i.toString)
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Parses result of WskActivation.list to extract sequence of activation ids.
   *
   * @param rr run result, should be from WhiskActivation.list otherwise behavior is undefined
   * @return sequence of activations
   */
  def ids(rr: RunResult): Seq[String] = {
    val lines = rr.stdout.split("\n")
    val header = lines(0)
    // old format has the activation id first, new format has activation id in third column
    val column = if (header.startsWith("activations")) 0 else 2
    lines.drop(1).map(_.split(" ")(column)) // drop the header and grab just the activationId column
  }

  /**
   * Gets activation by id.
   *
   * @param activationId the activation id
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   * @param last retrieves latest acitvation
   */
  override def get(activationId: Option[String] = None,
                   expectedExitCode: Int = SUCCESS_EXIT,
                   fieldFilter: Option[String] = None,
                   last: Option[Boolean] = None,
                   summary: Option[Boolean] = None)(implicit wp: WskProps): RunResult = {
    val params = {
      activationId map { a =>
        Seq(a)
      } getOrElse Seq.empty
    } ++ {
      fieldFilter map { f =>
        Seq(f)
      } getOrElse Seq.empty
    } ++ {
      last map { l =>
        Seq("--last")
      } getOrElse Seq.empty
    } ++ {
      summary map { s =>
        Seq("--summary")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ Seq(noun, "get", "--auth", wp.authKey) ++ params, expectedExitCode)
  }

  /**
   * Gets activation logs by id.
   *
   * @param activationId the activation id
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   * @param last retrieves latest acitvation
   */
  override def logs(activationId: Option[String] = None,
                    expectedExitCode: Int = SUCCESS_EXIT,
                    last: Option[Boolean] = None)(implicit wp: WskProps): RunResult = {
    val params = {
      activationId map { a =>
        Seq(a)
      } getOrElse Seq.empty
    } ++ {
      last map { l =>
        Seq("--last")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ Seq(noun, "logs", "--auth", wp.authKey) ++ params, expectedExitCode)
  }

  /**
   * Gets activation result by id.
   *
   * @param activationId the activation id
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   * @param last retrieves latest acitvation
   */
  override def result(activationId: Option[String] = None,
                      expectedExitCode: Int = SUCCESS_EXIT,
                      last: Option[Boolean] = None)(implicit wp: WskProps): RunResult = {
    val params = {
      activationId map { a =>
        Seq(a)
      } getOrElse Seq.empty
    } ++ {
      last map { l =>
        Seq("--last")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ Seq(noun, "result", "--auth", wp.authKey) ++ params, expectedExitCode)
  }

  /**
   * Polls activations list for at least N activations. The activations
   * are optionally filtered for the given entity. Will return as soon as
   * N activations are found. If after retry budget is exhausted, N activations
   * are still not present, will return a partial result. Hence caller must
   * check length of the result and not assume it is >= N.
   *
   * @param N the number of activations desired
   * @param entity the name of the entity to filter from activation list
   * @param limit the maximum number of entities to list (if entity name is not unique use Some(0))
   * @param since (optional) only the activations since this timestamp are included
   * @param skip (optional) the number of activations to skip
   * @param retries the maximum retries (total timeout is retries + 1 seconds)
   * @return activation ids found, caller must check length of sequence
   */
  override def pollFor(N: Int,
                       entity: Option[String],
                       limit: Option[Int] = None,
                       since: Option[Instant] = None,
                       skip: Option[Int] = Some(0),
                       retries: Int = 10,
                       pollPeriod: Duration = 1.second)(implicit wp: WskProps): Seq[String] = {
    Try {
      retry({
        val result = ids(list(filter = entity, limit = limit, since = since, skip = skip))
        if (result.length >= N) result else throw PartialResult(result)
      }, retries, waitBeforeRetry = Some(pollPeriod))
    } match {
      case Success(ids)                => ids
      case Failure(PartialResult(ids)) => ids
      case _                           => Seq.empty
    }
  }

  /**
   * Polls for an activation matching the given id. If found
   * return Right(activation) else Left(result of running CLI command).
   *
   * @return either Left(error message) or Right(activation as JsObject)
   */
  override def waitForActivation(activationId: String,
                                 initialWait: Duration = 1 second,
                                 pollPeriod: Duration = 1 second,
                                 totalWait: Duration = 30 seconds)(implicit wp: WskProps): Either[String, JsObject] = {
    val activation = waitfor(
      () => {
        val result =
          wsk
            .cli(wp.overrides ++ Seq(noun, "get", activationId, "--auth", wp.authKey), expectedExitCode = DONTCARE_EXIT)
        if (result.exitCode == NOT_FOUND) {
          null
        } else if (result.exitCode == SUCCESS_EXIT) {
          Right(result.stdout)
        } else Left(s"$result")
      },
      initialWait,
      pollPeriod,
      totalWait)

    Option(activation) map {
      case Right(stdout) =>
        Try {
          // strip first line and convert the rest to JsObject
          assert(stdout.startsWith("ok: got activation"))
          WskOperations.parseJsonString(stdout)
        } map {
          Right(_)
        } getOrElse Left(s"cannot parse activation from '$stdout'")
      case Left(error) => Left(error)
    } getOrElse Left(s"$activationId not found")
  }

  /** Used in polling for activations to record partial results from retry poll. */
  private case class PartialResult(ids: Seq[String]) extends Throwable
}

class CliNamespaceOperations(override val wsk: RunCliCmd)
    extends CliDeleteFromCollectionOperations
    with NamespaceOperations {

  protected val noun = "namespace"

  /**
   * Lists available namespaces for whisk key.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def list(expectedExitCode: Int = SUCCESS_EXIT, nameSort: Option[Boolean] = None)(
    implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "list", "--auth", wp.authKey) ++ {
      nameSort map { n =>
        Seq("--name-sort")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Looks up namespace for whisk props.
   *
   * @param wskprops instance of WskProps with an auth key to lookup
   * @return namespace as string
   */
  override def whois()(implicit wskprops: WskProps): String = {
    // the invariant that list() returns a conforming result is enforced in WskRestBasicTests
    val ns = list().stdout.linesIterator.toSeq.last.trim
    assert(ns != "_") // this is not permitted
    ns
  }

  /**
   * Gets entities in namespace.
   *
   * @param namespace (optional) if specified must be  fully qualified namespace
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def get(namespace: Option[String] = None, expectedExitCode: Int, nameSort: Option[Boolean] = None)(
    implicit wp: WskProps): RunResult = {
    val params = {
      nameSort map { n =>
        Seq("--name-sort")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ Seq(noun, "get", resolve(namespace), "--auth", wp.authKey) ++ params, expectedExitCode)
  }
}

class CliPackageOperations(override val wsk: RunCliCmd)
    extends CliListOrGetFromCollectionOperations
    with CliDeleteFromCollectionOperations
    with PackageOperations {
  override protected val noun = "package"

  /**
   * Creates package. Parameters mirror those available in the CLI.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      parameters: Map[String, JsValue] = Map.empty,
                      annotations: Map[String, JsValue] = Map.empty,
                      parameterFile: Option[String] = None,
                      annotationFile: Option[String] = None,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, if (!update) "create" else "update", "--auth", wp.authKey, fqn(name)) ++ {
      parameters flatMap { p =>
        Seq("-p", p._1, p._2.compactPrint)
      }
    } ++ {
      annotations flatMap { p =>
        Seq("-a", p._1, p._2.compactPrint)
      }
    } ++ {
      parameterFile map { pf =>
        Seq("-P", pf)
      } getOrElse Seq.empty
    } ++ {
      annotationFile map { af =>
        Seq("-A", af)
      } getOrElse Seq.empty
    } ++ {
      shared map { s =>
        Seq("--shared", if (s) "yes" else "no")
      } getOrElse Seq.empty
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }

  /**
   * Binds package. Parameters mirror those available in the CLI.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def bind(provider: String,
                    name: String,
                    parameters: Map[String, JsValue] = Map.empty,
                    annotations: Map[String, JsValue] = Map.empty,
                    expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "bind", "--auth", wp.authKey, fqn(provider), fqn(name)) ++ {
      parameters flatMap { p =>
        Seq("-p", p._1, p._2.compactPrint)
      }
    } ++ {
      annotations flatMap { p =>
        Seq("-a", p._1, p._2.compactPrint)
      }
    }
    wsk.cli(wp.overrides ++ params, expectedExitCode)
  }
}

class CliGatewayOperations(val wsk: RunCliCmd) extends GatewayOperations {
  protected val noun = "api"

  /**
   * Creates and API endpoint. Parameters mirror those available in the CLI.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(basepath: Option[String] = None,
                      relpath: Option[String] = None,
                      operation: Option[String] = None,
                      action: Option[String] = None,
                      apiname: Option[String] = None,
                      swagger: Option[String] = None,
                      responsetype: Option[String] = None,
                      expectedExitCode: Int = SUCCESS_EXIT,
                      cliCfgFile: Option[String] = None)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "create", "--auth", wp.authKey) ++ {
      basepath map { b =>
        Seq(b)
      } getOrElse Seq.empty
    } ++ {
      relpath map { r =>
        Seq(r)
      } getOrElse Seq.empty
    } ++ {
      operation map { o =>
        Seq(o)
      } getOrElse Seq.empty
    } ++ {
      action map { aa =>
        Seq(aa)
      } getOrElse Seq.empty
    } ++ {
      apiname map { a =>
        Seq("--apiname", a)
      } getOrElse Seq.empty
    } ++ {
      swagger map { s =>
        Seq("--config-file", s)
      } getOrElse Seq.empty
    } ++ {
      responsetype map { t =>
        Seq("--response-type", t)
      } getOrElse Seq.empty
    }
    wsk.cli(
      wp.overrides ++ params,
      expectedExitCode,
      showCmd = true,
      env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
  }

  /**
   * Retrieve a list of API endpoints. Parameters mirror those available in the CLI.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def list(basepathOrApiName: Option[String] = None,
                    relpath: Option[String] = None,
                    operation: Option[String] = None,
                    limit: Option[Int] = None,
                    since: Option[Instant] = None,
                    full: Option[Boolean] = None,
                    nameSort: Option[Boolean] = None,
                    expectedExitCode: Int = SUCCESS_EXIT,
                    cliCfgFile: Option[String] = None)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "list", "--auth", wp.authKey) ++ {
      basepathOrApiName map { b =>
        Seq(b)
      } getOrElse Seq.empty
    } ++ {
      relpath map { r =>
        Seq(r)
      } getOrElse Seq.empty
    } ++ {
      operation map { o =>
        Seq(o)
      } getOrElse Seq.empty
    } ++ {
      limit map { l =>
        Seq("--limit", l.toString)
      } getOrElse Seq.empty
    } ++ {
      since map { i =>
        Seq("--since", i.toEpochMilli.toString)
      } getOrElse Seq.empty
    } ++ {
      full map { r =>
        Seq("--full")
      } getOrElse Seq.empty
    } ++ {
      nameSort map { n =>
        Seq("--name-sort")
      } getOrElse Seq.empty
    }
    wsk.cli(
      wp.overrides ++ params,
      expectedExitCode,
      showCmd = true,
      env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
  }

  /**
   * Retieves an API's configuration. Parameters mirror those available in the CLI.
   * Runs a command wsk [params] where the arguments come in as a sequence.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def get(basepathOrApiName: Option[String] = None,
                   full: Option[Boolean] = None,
                   expectedExitCode: Int = SUCCESS_EXIT,
                   cliCfgFile: Option[String] = None,
                   format: Option[String] = None)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "get", "--auth", wp.authKey) ++ {
      basepathOrApiName map { b =>
        Seq(b)
      } getOrElse Seq.empty
    } ++ {
      full map { f =>
        if (f) Seq("--full") else Seq.empty
      } getOrElse Seq.empty
    } ++ {
      format map { ft =>
        Seq("--format", ft)
      } getOrElse Seq.empty
    }
    wsk.cli(
      wp.overrides ++ params,
      expectedExitCode,
      showCmd = true,
      env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
  }

  /**
   * Delete an entire API or a subset of API endpoints. Parameters mirror those available in the CLI.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def delete(basepathOrApiName: String,
                      relpath: Option[String] = None,
                      operation: Option[String] = None,
                      expectedExitCode: Int = SUCCESS_EXIT,
                      cliCfgFile: Option[String] = None)(implicit wp: WskProps): RunResult = {
    val params = Seq(noun, "delete", "--auth", wp.authKey, basepathOrApiName) ++ {
      relpath map { r =>
        Seq(r)
      } getOrElse Seq.empty
    } ++ {
      operation map { o =>
        Seq(o)
      } getOrElse Seq.empty
    }
    wsk.cli(
      wp.overrides ++ params,
      expectedExitCode,
      showCmd = true,
      env = Map("WSK_CONFIG_FILE" -> cliCfgFile.getOrElse("")))
  }
}

object Wsk {
  val binaryName = "wsk"
  val defaultCliPath = WhiskProperties.getCLIPath
}
