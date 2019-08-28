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

package org.apache.openwhisk.standalone

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{Accept, Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpHeader, HttpMethods, MediaTypes, StatusCodes, Uri}
import org.apache.commons.io.IOUtils
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.database.CouchDbRestClient
import org.apache.openwhisk.http.PoolingRestClient
import org.apache.openwhisk.http.PoolingRestClient._
import org.apache.openwhisk.standalone.StandaloneDockerSupport.{containerName, createRunCmd}
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

class CouchDBLauncher(docker: StandaloneDockerClient, port: Int, dataDir: File)(implicit logging: Logging,
                                                                                ec: ExecutionContext,
                                                                                actorSystem: ActorSystem,
                                                                                tid: TransactionId) {
  case class CouchDBConfig(image: String,
                           user: String,
                           password: String,
                           prefix: String,
                           subjectViews: List[String],
                           whiskViews: List[String],
                           activationViews: List[String])
  private val dbConfig = loadConfigOrThrow[CouchDBConfig](StandaloneConfigKeys.couchDBConfigKey)
  private val couchClient = new PoolingRestClient("http", StandaloneDockerSupport.getLocalHostName(), port, 100)
  private val baseHeaders: List[HttpHeader] =
    List(Authorization(BasicHttpCredentials(dbConfig.user, dbConfig.password)), Accept(MediaTypes.`application/json`))
  private val subjectDb = dbConfig.prefix + "subjects"
  private val activationsDb = dbConfig.prefix + "activations"
  private val whisksDb = dbConfig.prefix + "whisks"
  private val resourcePrefix = "couch"

  //TODO Log the server url
  def run(): Future[ServiceContainer] = {
    for {
      (_, dbSvcs) <- runCouch()
      _ <- waitForCouchDB()
      _ <- createDbIfNotExist("_users")
      _ <- createDbWithViews(subjectDb, dbConfig.subjectViews)
      _ <- createDbWithViews(whisksDb, dbConfig.whiskViews)
      _ <- createDbWithViews(activationsDb, dbConfig.activationViews)
    } yield dbSvcs
  }

  def runCouch(): Future[(StandaloneDockerContainer, ServiceContainer)] = {
    logging.info(this, s"Starting CouchDB at $port")
    val params = Map("-p" -> Set(s"$port:5984"))
    //TODO Local volume
    val env = Map("COUCHDB_USER" -> dbConfig.user, "COUCHDB_PASSWORD" -> dbConfig.password)
    val name = containerName("couch")
    val args = createRunCmd(name, env, params)
    val f = docker.runDetached(dbConfig.image, args, shouldPull = true)
    val sc = ServiceContainer(port, "CouchDB", name)
    f.map(c => (c, sc))
  }

  def waitForCouchDB(): Future[Done] = {
    new ServerStartupCheck(Uri(s"http://${StandaloneDockerSupport.getLocalHostName()}:$port/_utils/"), "CouchDB")
      .waitForServerToStart()
    Future.successful(Done)
  }

  private def createDbWithViews(dbName: String, views: List[String]): Future[Done] = {
    for {
      _ <- createDbIfNotExist(dbName)
      _ <- createDocs(views, dbName)
    } yield Done
  }

  private def createDbIfNotExist(dbName: String): Future[Done] = {
    for {
      userDbExist <- doesDbExist(dbName)
      _ <- if (userDbExist) Future.successful(Done) else createDb(dbName)
    } yield Done
  }

  private def doesDbExist(dbName: String): Future[Boolean] = {
    couchClient
      .requestJson[JsObject](mkRequest(HttpMethods.HEAD, uri(dbName), headers = baseHeaders))
      .map {
        case Right(_)                   => true
        case Left(StatusCodes.NotFound) => false
        case Left(s)                    => throw new IllegalStateException(("Unknown status code while checking user db" + s))
      }
  }

  private def createDb(dbName: String): Future[Done] = {
    couchClient
      .requestJson[JsObject](mkRequest(HttpMethods.PUT, uri(dbName), headers = baseHeaders))
      .map {
        case Right(_) => Done
        case Left(s)  => throw new IllegalStateException(("Unknown status code while creating user db" + s))
      }
  }

  private def createDocs(jsonFiles: List[String], dbName: String): Future[Done] = {
    val client = createDbClient(dbName)
    val f = jsonFiles
      .map { p =>
        val s = IOUtils.resourceToString(s"/$resourcePrefix/$p", UTF_8)
        val js = s.parseJson.asJsObject
        logging.info(this, s"Creating view doc from file $p for db $dbName")
        createDocIfRequired(js, dbName, client)
      }

    Future.sequence(f).map(_ => client.shutdown()).map(_ => Done)
  }

  private def createDocIfRequired(doc: JsObject, dbName: String, client: CouchDbRestClient): Future[Done] = {
    val id = doc.fields("_id").convertTo[String]
    for {
      jsOpt <- getDoc(id, client)
      _ <- jsOpt match {
        case Some(js) => {
          val rev = js.fields("_rev").convertTo[String]
          val docWithRev = JsObject(doc.fields + ("_rev" -> JsString(rev)))
          if (docWithRev != js) {
            logging.info(this, s"Updating doc $id for db ${dbName}")
            createDoc(id, Some(rev), doc, client)
          } else Future.successful(Done)
        }
        case None => {
          logging.info(this, s"Creating doc $id for db ${dbName}")
          createDoc(id, None, doc, client)
        }
      }
    } yield Done
  }

  private def getDoc(id: String, client: CouchDbRestClient): Future[Option[JsObject]] = {
    client.getDoc(id).map {
      case Right(js)                  => Some(js)
      case Left(StatusCodes.NotFound) => None
      case Left(s)                    => throw new IllegalStateException(s"Unknown status code while fetching doc $id" + s)
    }
  }

  private def createDoc(id: String, rev: Option[String], doc: JsObject, client: CouchDbRestClient): Future[Done] = {
    val f = rev match {
      case Some(r) => client.putDoc(id, r, doc)
      case None    => client.putDoc(id, doc)
    }
    f.map {
      case Right(_) => Done
      case Left(s)  => throw new IllegalStateException(s"Unknown status code while creating doc $id" + s)
    }
  }

  // Properly encodes the potential slashes in each segment.
  private def uri(segments: Any*): Uri = {
    val encodedSegments = segments.map(s => URLEncoder.encode(s.toString, UTF_8.name))
    Uri(s"/${encodedSegments.mkString("/")}")
  }

  private def createDbClient(dbName: String) =
    new NonEscapingClient(
      "http",
      StandaloneDockerSupport.getLocalHostName(),
      port,
      dbConfig.user,
      dbConfig.password,
      dbName)(actorSystem, logging)
}

private class NonEscapingClient(protocol: String,
                                host: String,
                                port: Int,
                                username: String,
                                password: String,
                                db: String)(implicit system: ActorSystem, logging: Logging)
    extends CouchDbRestClient(protocol, host, port, username, password, db) {

  //Do not escape the design doc id like _design/subjects etc
  override protected def uri(segments: Any*): Uri = Uri(s"/${segments.mkString("/")}")
}
