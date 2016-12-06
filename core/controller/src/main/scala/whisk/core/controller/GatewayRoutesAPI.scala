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

package whisk.core.controller

import spray.json._
import DefaultJsonProtocol._
import scala.concurrent._
import spray.http._
import akka.actor.ActorSystem
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._
import spray.routing.Directive.pimpApply
import spray.routing._, Directives._
import spray.httpx.SprayJsonSupport._
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import akka.event.Logging.LogLevel
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.http.StatusCodes._
import whisk.core.entity._
import whisk.common.{  Logging }
import scala.util.Success
import whisk.core.entity.EntityPath
import whisk.core.entity.Identity
import scala.concurrent._
import scala.concurrent.{ Future , Promise }
import scala.concurrent.duration._
import scala.util.{Failure,Success}
import whisk.core.entity.WhiskAuth
import whisk.core.entity.Subject
import spray.http._
import spray.client.pipelining._
import akka.actor.ActorSystem
import scala.concurrent.Future
import spray.http.BasicHttpCredentials
import scala.concurrent.ExecutionContext.Implicits.global
import spray.http.Uri
import spray.http.Uri.Path
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.entity._
import whisk.core.entity.{WhiskAuthStore}
import akka.event.Logging.{ LogLevel }

class gatewayRoutesApi(
        val apipath: String,
        val apiversion: String,
        val verbosity: LogLevel,
        val config: WhiskConfig,
        implicit val actorSystem: ActorSystem)
        extends Logging {
        protected implicit val authStore = WhiskAuthStore.datastore(config)
        private val url = Uri(s"http://localhost:${config.servicePort}")
        private val key = Await.result(WhiskAuth.get(authStore, Subject("whisk.system"),false)(TransactionId.unknown), 5.seconds)
        private val validCredentials = BasicHttpCredentials(key.authkey.uuid(), key.authkey.key())
        /**
        * Handles invoking create action
        *
        * @return Future[JsObject] from action result
        */
        def invokeActionCreate(requestBody: JsObject ) : Future[JsObject]  = {
            val actionPath = Path("/api/"+apiversion) / "namespaces" / "whisk.system" / "actions" / "routemgmt" / "createRoute"
            val pipeline: HttpRequest => Future[HttpResponse] = (
                addCredentials(validCredentials)
                   ~> sendReceive
            )
            val p = Promise[JsObject]()
            val response: Future[HttpResponse] = pipeline(Post(url.withPath(actionPath).toString.concat("?blocking=true"),  requestBody)  )
            response.onComplete({
                case Success(result: HttpResponse) => {
                  p trySuccess result.entity.asString.parseJson.asJsObject
                }
                case Failure(error) => {
                  p tryFailure error
                }
             })
             p.future
       }
       /**
        ** Handles invoking get actions
        **
        * @return Future[String] from action result
        */
        def invokeActionGet(requestBody: JsObject ) : Future[JsObject]  = {
            info(this, s"APIGatewayRoutesGetInget")
            val actionPath = Path("/api/"+apiversion) / "namespaces" / "whisk.system" / "actions" / "routemgmt" / "getApi"
            val pipeline: HttpRequest => Future[HttpResponse] = (
                addCredentials(validCredentials)
                    ~> sendReceive
            )
            val p = Promise[JsObject]()
            val response: Future[HttpResponse] = pipeline(Post(url.withPath(actionPath).toString.concat("?blocking=true"),  requestBody)  )
            response.onComplete({
                case Success(result: HttpResponse) => {
                    p trySuccess result.entity.asString.parseJson.asJsObject
                }
                case Failure(error) => {
                    p tryFailure error
                }
            })
            p.future
        }
        /**
        * Handles invoking delete action
        **
        * @return Future[String] from action result
        */
        def invokeActionDelete(requestBody: JsObject ) : Future[JsObject]  = {
            val actionPath = Path("/api/"+apiversion) / "namespaces" / "whisk.system" / "actions" / "routemgmt" / "deleteApi"
            val pipeline: HttpRequest => Future[HttpResponse] = (
                    addCredentials(validCredentials)
                             ~> sendReceive
            )
            val p = Promise[JsObject]()
            val response: Future[HttpResponse] = pipeline(Post(url.withPath(actionPath).toString.concat("?blocking=true"), requestBody)  )
            response.onComplete({
                case Success(result: HttpResponse) => {
                   p trySuccess result.entity.asString.parseJson.asJsObject
                }
                case Failure(error) => {
                   p tryFailure error
                }
            })
            p.future
      }
      /**
      * Handles the controller's api gateway routes
      */
      def routes(user: Identity)(implicit transid: TransactionId) = {
       lazy val collectionPrefix = pathPrefix( (EntityPath.DEFAULT.toString.r | Segment) / "routes")
       get {
         collectionPrefix { segment => {
           entity(as[String]) { pattern =>
             parameters("basepath"?"", "operation"?"","relpath"?"") { (basepath, operation,relpath) =>
               var postBody = JsObject(("namespace" -> JsString(segment)), ("basepath" -> JsString(basepath)),("operation" -> JsString(operation)),("relpath" -> JsString(relpath)))
               if(operation == "" && relpath == ""){
                   postBody = JsObject(("namespace" -> JsString(segment)), ("basepath" -> JsString(basepath)));
               }
               if(operation == "" && relpath != "" ){
                   postBody = JsObject(("namespace" -> JsString(segment)), ("basepath" -> JsString(basepath)),("relpath" -> JsString(relpath)))
               }
               if(operation != "" && relpath ==""){
                   postBody = JsObject(("namespace" -> JsString(segment)), ("basepath" -> JsString(basepath)),("operation" -> JsString(operation)))
               }
               info(this, s"APIGatewayRoutesGetBody '${postBody}'")
               complete (OK, invokeActionGet(postBody))
              }
            }
          }
        }
       } ~
       post {
         collectionPrefix { segment => {
             entity(as[JsObject]) { pattern =>
                info(this, s"APIGatewayRoutesPostBody '${pattern}'")
                complete (OK, invokeActionCreate(pattern))
             }
            }
          }
       } ~
       delete{
         collectionPrefix { segment => {
           entity(as[String]) { pattern =>
               parameters("basepath", "operation" ?"","relpath"?"","force"?"false") { (basepath, operation,relpath,force) =>
                 var postBody = JsObject(("namespace" -> JsString(segment)), ("basepath" -> JsString(basepath)),("force" -> JsBoolean(force.toBoolean)),("operation" -> JsString(operation)),("relpath" -> JsString(relpath)))
                 if(operation == "" && relpath == ""){
                   postBody = JsObject(("namespace" -> JsString(segment)),("force" -> JsBoolean(force.toBoolean)),("basepath" -> JsString(basepath)))
                 }
                 if(operation == "" && relpath != "" ){
                   postBody = JsObject(("namespace" -> JsString(segment)), ("basepath" -> JsString(basepath)),("force" -> JsBoolean(force.toBoolean)),("relpath" -> JsString(relpath)))
                 }
                 if(operation != "" && relpath ==""){
                   postBody = JsObject(("namespace" -> JsString(segment)), ("basepath" -> JsString(basepath)),("force" -> JsBoolean(force.toBoolean)),("operation" -> JsString(operation)))
                 }
                 info(this, s"APIGatewayRoutesDeleteBody '${pattern}'")
                 complete (OK, invokeActionDelete(postBody))
              }
             }
            }
           }
         }
       }
}
