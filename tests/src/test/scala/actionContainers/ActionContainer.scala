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

package actionContainers

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter

import scala.util.Try
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.sys.process.ProcessLogger
import scala.sys.process.stringToProcess
import scala.util.Random
import scala.util.{Failure, Success}
import org.apache.commons.lang3.StringUtils
import org.scalatest.{FlatSpec, Matchers}
import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext
import spray.json._
import common.StreamLogging
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.Exec
import common.WhiskProperties
import org.apache.openwhisk.core.containerpool.Container

/**
 * For testing convenience, this interface abstracts away the REST calls to a
 * container as blocking method calls of this interface.
 */
trait ActionContainer {
  def init(value: JsValue): (Int, Option[JsObject])
  def run(value: JsValue): (Int, Option[JsObject])
  def runMultiple(values: Seq[JsValue])(implicit ec: ExecutionContext): Seq[(Int, Option[JsObject])]
}

trait ActionProxyContainerTestUtils extends FlatSpec with Matchers with StreamLogging {
  import ActionContainer.{filterSentinel, sentinel}

  def initPayload(code: String, main: String = "main", env: Option[Map[String, JsString]] = None): JsObject =
    JsObject(
      "value" -> JsObject(
        "code" -> { if (code != null) JsString(code) else JsNull },
        "main" -> JsString(main),
        "binary" -> JsBoolean(Exec.isBinaryCode(code)),
        "env" -> env.map(JsObject(_)).getOrElse(JsNull)))

  def runPayload(args: JsValue, other: Option[JsObject] = None): JsObject =
    JsObject(Map("value" -> args) ++ (other map { _.fields } getOrElse Map.empty))

  def checkStreams(out: String,
                   err: String,
                   additionalCheck: (String, String) => Unit,
                   sentinelCount: Int = 1,
                   concurrent: Boolean = false): Unit = {
    withClue("expected number of stdout sentinels") {
      sentinelCount shouldBe StringUtils.countMatches(out, sentinel)
    }
    //sentinels should be all together
    if (concurrent) {
      withClue("expected grouping of stdout sentinels") {
        out should include((1 to sentinelCount).map(_ => sentinel + "\n").mkString)
      }
    }
    withClue("expected number of stderr sentinels") {
      sentinelCount shouldBe StringUtils.countMatches(err, sentinel)
    }
    //sentinels should be all together
    if (concurrent) {
      withClue("expected grouping of stderr sentinels") {
        err should include((1 to sentinelCount).map(_ => sentinel + "\n").mkString)
      }
    }

    val (o, e) = (filterSentinel(out), filterSentinel(err))
    o should not include sentinel
    e should not include sentinel
    additionalCheck(o, e)
  }
}

object ActionContainer {
  private lazy val dockerBin: String = {
    List("/usr/bin/docker", "/usr/local/bin/docker").find { bin =>
      new File(bin).isFile
    }.get // This fails if the docker binary couldn't be located.
  }

  lazy val dockerCmd: String = {
    /*
     * The docker host is set to a provided property 'docker.host' if it's
     * available; otherwise we check with WhiskProperties to see whether we are
     * running on a docker-machine.
     *
     * IMPLICATION:  The test must EITHER have the 'docker.host' system
     * property set OR the 'OPENWHISK_HOME' environment variable set and a
     * valid 'whisk.properties' file generated.  The 'docker.host' system
     * property takes precedence.
     *
     * WARNING:  Adding a non-docker-machine environment that contains 'mac'
     * (i.e. 'environments/local-mac') will likely break things.
     *
     * The plan is to move builds to using 'gradle-docker-plugin', which know
     * its docker socket and to have it pass the docker socket implicitly using
     * 'systemProperty "docker.host", docker.url'.  Eventually, we will also
     * need to handle TLS certificates here.  Again, 'gradle-docker-plugin'
     * knows where they are; we will just add system properties to get the
     * information onto the docker command line.
     */
    val dockerCmdString = dockerBin +
      sys.props
        .get("docker.host")
        .orElse(sys.env.get("DOCKER_HOST"))
        .orElse {
          Try { // whisk.properties file may not exist
            // Check if we are running on docker-machine env.
            Option(WhiskProperties.getProperty("environment.type"))
              .filter(_.toLowerCase.contains("docker-machine"))
              .map {
                case _ => s"tcp://${WhiskProperties.getMainDockerEndpoint}"
              }
          }.toOption.flatten
        }
        .map(" --host " + _)
        .getOrElse("")

    // Test here that this actually works, otherwise throw a somewhat understandable error message
    proc(s"$dockerCmdString info").onComplete {
      case Success((v, _, _)) if v != 0 =>
        throw new RuntimeException(s"""
              |Unable to connect to docker host using $dockerCmdString as command string.
              |The docker host is determined using the Java property 'docker.host' or
              |the environment variable 'DOCKER_HOST'. Please verify that one or the
              |other is set for your build/test process.""".stripMargin)
      case Success((v, _, _)) if v == 0 => // Do nothing
      case Failure(t)                   => throw t
    }

    dockerCmdString
  }

  private def docker(command: String): String = s"$dockerCmd $command"

  // Runs a process asynchronously. Returns a future with (exitCode,stdout,stderr)
  private def proc(cmd: String): Future[(Int, String, String)] = Future {
    blocking {
      val out = new ByteArrayOutputStream
      val err = new ByteArrayOutputStream
      val outW = new PrintWriter(out)
      val errW = new PrintWriter(err)
      val v = cmd ! ProcessLogger(o => outW.println(o), e => errW.println(e))
      outW.close()
      errW.close()
      (v, out.toString, err.toString)
    }
  }

  // Tying it all together, we have a method that runs docker, waits for
  // completion for some time then returns the exit code, the output stream
  // and the error stream.
  private def awaitDocker(cmd: String, t: Duration): (Int, String, String) = {
    Await.result(proc(docker(cmd)), t)
  }

  // Filters out the sentinel markers inserted by the container (see relevant private code in Invoker)
  val sentinel = Container.ACTIVATION_LOG_SENTINEL
  def filterSentinel(str: String): String = str.replaceAll(sentinel, "").trim

  def withContainer(imageName: String, environment: Map[String, String] = Map.empty)(
    code: ActionContainer => Unit)(implicit actorSystem: ActorSystem, logging: Logging): (String, String) = {
    val rand = { val r = Random.nextInt; if (r < 0) -r else r }
    val name = imageName.toLowerCase.replaceAll("""[^a-z]""", "") + rand
    val envArgs = environment.toSeq
      .map {
        case (k, v) => s"-e $k=$v"
      }
      .mkString(" ")

    // We create the container... and find out its IP address...
    def createContainer(portFwd: Option[Int] = None): Unit = {
      val runOut = awaitDocker(
        s"run ${portFwd.map(p => s"-p $p:8080").getOrElse("")} --name $name $envArgs -d $imageName",
        60.seconds)
      assert(runOut._1 == 0, "'docker run' did not exit with 0: " + runOut)
    }

    // ...find out its IP address...
    val (ip, port) =
      if (System.getProperty("os.name").toLowerCase().contains("mac") && !sys.env
            .get("DOCKER_HOST")
            .exists(_.trim.nonEmpty)) {
        // on MacOSX, where docker for mac does not permit communicating with container directly
        val p = 8988 // port must be available or docker run will fail
        createContainer(Some(p))
        Thread.sleep(1500) // let container/server come up cleanly
        ("localhost", p)
      } else {
        // not "mac" i.e., docker-for-mac, use direct container IP directly (this is OK for Ubuntu, and docker-machine)
        createContainer()
        val ipOut = awaitDocker(s"""inspect --format '{{.NetworkSettings.IPAddress}}' $name""", 10.seconds)
        assert(ipOut._1 == 0, "'docker inspect did not exit with 0")
        (ipOut._2.replaceAll("""[^0-9.]""", ""), 8080)
      }

    // ...we create an instance of the mock container interface...
    val mock = new ActionContainer {
      def init(value: JsValue): (Int, Option[JsObject]) = syncPost(ip, port, "/init", value)
      def run(value: JsValue): (Int, Option[JsObject]) = syncPost(ip, port, "/run", value)
      def runMultiple(values: Seq[JsValue])(implicit ec: ExecutionContext): Seq[(Int, Option[JsObject])] =
        concurrentSyncPost(ip, port, "/run", values)
    }

    try {
      // ...and finally run the code with it.
      code(mock)
      // I'm told this is good for the logs.
      Thread.sleep(100)
      val (_, out, err) = awaitDocker(s"logs $name", 10.seconds)
      (out, err)
    } finally {
      awaitDocker(s"kill $name", 10.seconds)
      awaitDocker(s"rm $name", 10.seconds)
    }
  }

  private def syncPost(host: String, port: Int, endPoint: String, content: JsValue)(
    implicit logging: Logging,
    as: ActorSystem): (Int, Option[JsObject]) = {

    implicit val transid = TransactionId.testing

    org.apache.openwhisk.core.containerpool.AkkaContainerClient.post(host, port, endPoint, content, 30.seconds)
  }
  private def concurrentSyncPost(host: String, port: Int, endPoint: String, contents: Seq[JsValue])(
    implicit logging: Logging,
    as: ActorSystem): Seq[(Int, Option[JsObject])] = {

    implicit val transid = TransactionId.testing

    org.apache.openwhisk.core.containerpool.AkkaContainerClient
      .concurrentPost(host, port, endPoint, contents, 30.seconds)
  }

}
