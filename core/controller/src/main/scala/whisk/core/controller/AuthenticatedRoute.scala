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

package whisk.core.controller

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import akka.http.scaladsl.server.{AuthenticationFailedRejection, Directive1, Route}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.directives._
import akka.http.scaladsl.server.directives.AuthenticationResult
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import akka.http.scaladsl.server.directives.BasicDirectives.provide
import akka.http.scaladsl.server.directives.FutureDirectives.onSuccess
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import whisk.common.{PrintStreamLogging, TransactionId}
import whisk.core.WhiskConfig
import whisk.core.entity.{EntityName, Identity, Subject}


/** A common trait for secured routes */
trait AuthenticatedRoute {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /*** Auth route for basic auth and certificate auth*/
    def authenticate(implicit transid: TransactionId) = {
        implicit val logger = new PrintStreamLogging()
        val config = new WhiskConfig(Map("whisk.ssl.client.verification" -> null))
        if (config.sslClientVerification == "off"){
            basicAuth(validateCredentials)
        }else{
            certificateAuth(validateCertificate)
        }
    }

    /** Creates HTTP BasicAuth handler */
    def basicAuth[A](verify: Option[BasicHttpCredentials] => Future[Option[A]]) = {
        authenticateOrRejectWithChallenge[BasicHttpCredentials, A] { creds =>
            verify(creds).map {
                case Some(t) => AuthenticationResult.success(t)
                case None    => AuthenticationResult.failWithChallenge(HttpChallenges.basic("OpenWhisk secure realm"))
            }
        }
    }


    /** Create certificate auth handler */
    def certificateAuth[A](verify: Option[CertificateInfo] => Future[Option[A]]):AuthenticationDirective[A]  = {
        val result = extract { ctx =>
            val authHeader = ctx.request.headers.find(_.is("x-ssl-subject"))
            val subjectHeader = authHeader flatMap {
                case header =>
                    header.value.split(",").find(_.startsWith("CN="))
            }
            val subject: Option[Subject] = subjectHeader map {
                case subject => Subject(subject.substring("CN=".length, subject.length))
            }
            val namespaceHeader = ctx.request.headers.find(_.is("namespace"))
            val certificateInfo: Option[CertificateInfo] = namespaceHeader map {
                case header if header.value() == "_" => CertificateInfo(EntityName(subject.get.asString), subject.get)
                case header if header.value() != "_" => CertificateInfo(EntityName(header.value), subject.get)
            }
            val certificateFuture = verify(certificateInfo).map {
                case Some(t) => AuthenticationResult.success(t)
                case None    => AuthenticationResult.failWithChallenge(HttpChallenges.basic("OpenWhisk secure realm"))
            }
            onSuccess(certificateFuture).flatMap {
                case Right(user) ⇒ provide(user)
                case Left(challenge) ⇒
                    val cause = if (authHeader.isEmpty) CredentialsMissing else CredentialsRejected
                    reject(AuthenticationFailedRejection(cause, challenge)): Directive1[A]
            }
        }
        result.flatMap { user =>
            user
        }
    }

    /** Validates credentials against database of subjects */
    protected def validateCredentials(credentials: Option[BasicHttpCredentials])(implicit transid: TransactionId): Future[Option[Identity]]

    /** Validates client certificate against database of subjects */
    protected def validateCertificate(certificateInfo: Option[CertificateInfo])(implicit transid: TransactionId): Future[Option[Identity]]
}

/** A trait for authenticated routes. */
trait AuthenticatedRouteProvider {
    def routes(user: Identity)(implicit transid: TransactionId): Route
}
