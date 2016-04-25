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

import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.util.Random

import java.io.File

import common.WhiskProperties

import scala.util.Try
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._

import java.io.ByteArrayOutputStream
import java.io.PrintWriter

/**
 * For testing convenience, this interface abstracts away the REST calls to a
 * container as blocking method calls of this interface.
 */
trait ActionContainer {
    def init(value: JsValue): (Int, Option[JsObject])
    def run(value: JsValue): (Int, Option[JsObject])
}

object ActionContainer {
    private lazy val dockerBin: String = {
        List("/usr/bin/docker", "/usr/local/bin/docker").find { bin =>
            new File(bin).isFile()
        }.getOrElse(???) // This fails if the docker binary couln't be located.
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

    def withContainer(imageName: String)(code: ActionContainer => Unit): (String, String) = {
        val rand = { val r = Random.nextInt; if (r < 0) -r else r }
        val name = imageName.toLowerCase.replaceAll("""[^a-z]""", "") + rand

        // We create the container...
        val runOut = awaitDocker(s"run --name $name -d $imageName", 10.seconds)
        assert(runOut._1 == 0, "'docker run' did not exit with 0: " + runOut)

        // ...find out its IP address...
        val ipOut = awaitDocker(s"""inspect --format '{{.NetworkSettings.IPAddress}}' $name""", 10.seconds)
        assert(ipOut._1 == 0, "'docker inspect did not exit with 0")
        val ip = ipOut._2.replaceAll("""[^0-9.]""", "")

        // ...we create an instance of the mock container interface...
        val mock = new ActionContainer {
            def init(value: JsValue) = syncPost(s"$ip:8080", "/init", value)
            def run(value: JsValue) = syncPost(s"$ip:8080", "/run", value)
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

    private def syncPost(host: String, endPoint: String, content: JsValue): (Int, Option[JsObject]) = {
        import whisk.common.HttpUtils
        val connection = HttpUtils.makeHttpClient(30000, true)
        val (code, bytes) = new HttpUtils(connection, host).dopost(endPoint, content, Map.empty)
        val str = new java.lang.String(bytes)
        val json = Try(str.parseJson.asJsObject).toOption
        Try { connection.close() }
        (code, json)
    }

    private class ActionContainerImpl() extends ActionContainer {
        override def init(value: JsValue) = ???
        override def run(value: JsValue) = ???
    }
}
