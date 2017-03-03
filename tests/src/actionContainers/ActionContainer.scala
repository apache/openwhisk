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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.sys.process.ProcessLogger
import scala.sys.process.stringToProcess
import scala.util.Random
import scala.util.Try

import org.apache.commons.lang3.StringUtils
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import akka.actor.ActorSystem
import common.WhiskProperties
import spray.json._
import whisk.core.entity.Exec

/**
 * For testing convenience, this interface abstracts away the REST calls to a
 * container as blocking method calls of this interface.
 */
trait ActionContainer {
    def init(value: JsValue): (Int, Option[JsObject])
    def run(value: JsValue): (Int, Option[JsObject])
}

trait ActionProxyContainerTestUtils extends FlatSpec with Matchers {
    import ActionContainer.{ filterSentinel, sentinel }

    def initPayload(code: String, main: String = "main") = {
        JsObject("value" -> JsObject(
            "code" -> { if (code != null) JsString(code) else JsNull },
            "main" -> JsString(main),
            "binary" -> JsBoolean(Exec.isBinaryCode(code))))
    }

    def runPayload(args: JsValue, other: Option[JsObject] = None) = {
        JsObject(Map("value" -> args) ++ (other map { _.fields } getOrElse Map()))
    }

    def checkStreams(out: String, err: String, additionalCheck: (String, String) => Unit, sentinelCount: Int = 1) = {
        withClue("expected number of stdout sentinels") {
            sentinelCount shouldBe StringUtils.countMatches(out, sentinel)
        }
        withClue("expected number of stderr sentinels") {
            sentinelCount shouldBe StringUtils.countMatches(err, sentinel)
        }

        val (o, e) = (filterSentinel(out), filterSentinel(err))
        o should not include (sentinel)
        e should not include (sentinel)
        additionalCheck(o, e)
    }
}

object ActionContainer {
    private lazy val dockerBin: String = {
        List("/usr/bin/docker", "/usr/local/bin/docker").find { bin =>
            new File(bin).isFile()
        }.getOrElse(???) // This fails if the docker binary couldn't be located.
    }

    private lazy val dockerCmd: String = {
        val hostStr = if (WhiskProperties.onMacOSX()) {
            s" --host tcp://${WhiskProperties.getMainDockerEndpoint()} "
        } else {
            " "
        }
        s"$dockerBin $hostStr"
    }

    private def docker(command: String): String = s"$dockerCmd $command"

    // Runs a process asynchronously. Returns a future with (exitCode,stdout,stderr)
    private def proc(cmd: String): Future[(Int, String, String)] = Future {
        blocking {
            val out = new ByteArrayOutputStream
            val err = new ByteArrayOutputStream
            val outW = new PrintWriter(out)
            val errW = new PrintWriter(err)
            val v = cmd ! (ProcessLogger(outW.println, errW.println))
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

    // Filters out the sentinel markers inserted by the container (see relevant private code in Invoker.scala)
    val sentinel = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"
    def filterSentinel(str: String) = str.replaceAll(sentinel, "").trim

    def withContainer(imageName: String, environment: Map[String, String] = Map.empty)(
        code: ActionContainer => Unit)(implicit actorSystem: ActorSystem): (String, String) = {
        val rand = { val r = Random.nextInt; if (r < 0) -r else r }
        val name = imageName.toLowerCase.replaceAll("""[^a-z]""", "") + rand
        val envArgs = environment.toSeq.map {
            case (k, v) => s"-e ${k}=${v}"
        } mkString (" ")

        // We create the container...
        val runOut = awaitDocker(s"run --name $name $envArgs -d $imageName", 10 seconds)
        assert(runOut._1 == 0, "'docker run' did not exit with 0: " + runOut)

        // ...find out its IP address...
        val ipOut = awaitDocker(s"""inspect --format '{{.NetworkSettings.IPAddress}}' $name""", 10 seconds)
        assert(ipOut._1 == 0, "'docker inspect did not exit with 0")
        val ip = ipOut._2.replaceAll("""[^0-9.]""", "")

        // ...we create an instance of the mock container interface...
        val mock = new ActionContainer {
            def init(value: JsValue) = syncPost(ip, 8080, "/init", value)
            def run(value: JsValue) = syncPost(ip, 8080, "/run", value)
        }

        try {
            // ...and finally run the code with it.
            code(mock)
            // I'm told this is good for the logs.
            Thread.sleep(100)
            val (_, out, err) = awaitDocker(s"logs $name", 10 seconds)
            (out, err)
        } finally {
            awaitDocker(s"kill $name", 10 seconds)
            awaitDocker(s"rm $name", 10 seconds)
        }
    }

    private def syncPost(host: String, port: Int, endPoint: String, content: JsValue)(
        implicit actorSystem: ActorSystem): (Int, Option[JsObject]) = {
        import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
        import akka.http.scaladsl.marshalling._
        import akka.http.scaladsl.model._
        import akka.http.scaladsl.unmarshalling._
        import akka.stream.ActorMaterializer
        import whisk.core.container.AkkaHttpUtils

        implicit val materializer = ActorMaterializer()

        val uri = Uri(
            scheme = "http",
            authority = Uri.Authority(host = Uri.Host(host), port = port),
            path = Uri.Path(endPoint))

        val f = for (
            entity <- Marshal(content).to[MessageEntity];
            request = HttpRequest(method = HttpMethods.POST, uri = uri, entity = entity);
            response <- AkkaHttpUtils.singleRequest(request, 60.seconds, retryOnTCPErrors = true);
            responseBody <- Unmarshal(response.entity).to[String]
        ) yield (response.status.intValue, Try(responseBody.parseJson.asJsObject).toOption)

        Await.result(f, 1.minute)
    }

    private class ActionContainerImpl() extends ActionContainer {
        override def init(value: JsValue) = ???
        override def run(value: JsValue) = ???
    }
}
